
import com.mphrx.commons.interfaces.IRequestHandlerChain
import com.mphrx.util.grails.ApplicationContextUtil
import grails.transaction.Transactional
import org.apache.log4j.Logger
import com.mongodb.*
import com.mphrx.commons.services.ChainExecutionContext
import grails.plugins.rest.client.RestBuilder
import org.joda.time.DateTime
import org.codehaus.groovy.grails.web.json.JSONObject
import com.mphrx.chingari.consent.v1.MoConsentV1Service
import com.mphrx.auth.CitadelAuthService
import com.mphrx.auth.User
import com.mphrx.util.user.LoggedInUser
import com.mphrx.drools.CitadelRequestBean
import com.mphrx.chingari.mo.patient.v1.MoPatientBoV1
import java.text.SimpleDateFormat;
import com.mphrx.commons.consus.MongoService
import consus.resources.PatientResource


@Transactional
class WidgetAdapterService implements IRequestHandlerChain {

    private Logger log = Logger.getLogger(IRequestHandlerChain.class);
    ConfigObject config = ApplicationContextUtil.getConfig()
    RestBuilder rest = new RestBuilder();
    MoConsentV1Service moConsentV1Service = ApplicationContextUtil.getBeanByName("moConsentV1Service");
    CitadelAuthService citadelAuthService = ApplicationContextUtil.getBeanByName("citadelAuthService");
    MongoService mongoService = ApplicationContextUtil.getBeanByName("mongoService");
    def pat_cpf = null;
    def consentVersionPickedFromCollection = null
    def consentText = ""

    Map consentForm(final ChainExecutionContext chainExecutionContext) {
        Map jsonObject = chainExecutionContext.request
        Map response = chainExecutionContext.response
        Map data = chainExecutionContext.data
        List patientDetailsMapList = []
        Map patientDetailsMap = [:]

        JSONObject consentObj

        Map requestMap = [
                "constraints": [
                        "_sort:desc": "period.startDate",
                        "status:in" : "active,pendingOTP,proposed,suspended"
                ]
        ]
        User currentUser = new LoggedInUser().getCurrentUser()
        Map constraintMap = ["controller": "MoConsent", "action": "fetchLinkedPatients"]
        CitadelRequestBean citadelRequestBean = citadelAuthService.getCitadelRequestBeanFromUserAndParams(constraintMap, currentUser)
        citadelRequestBean = citadelAuthService.authorizeRequest(citadelRequestBean)
        requestMap?.paramsMap = ["ruleBean" : citadelRequestBean]
        log.info("self patient request map - "+requestMap?.paramsMap)
        log.info("[consentForm()] requestMap : "+requestMap)

        def resp = moConsentV1Service.fetchLinkedPatients(requestMap)
        log.info("[consentForm()] fetchLinkedPatients method response : "+resp)

        MoPatientBoV1 selfPatientDetails = resp?.get("selfPatientDetails")
        log.info("[consentForm()] selfPatientDetails : " + selfPatientDetails)

        if(selfPatientDetails) {
            consentObj = getConsent(selfPatientDetails?.id?.toString())
            PatientResource patientResource = new PatientResource().getPersistedObject(selfPatientDetails?.id)
            for(obj in patientResource?.identifier){
                if(obj?.system?.value == "Governo Federal")
                {
                    pat_cpf = obj?.value?.value
                }
            }
            patientDetailsMap?.put("id", selfPatientDetails?.id)
            patientDetailsMap?.put("name", selfPatientDetails?.firstName)
            patientDetailsMap?.put("relationship", selfPatientDetails?.relationship)
            patientDetailsMap?.put("dob", (selfPatientDetails?.dob) ? new SimpleDateFormat("MM/dd/yyyy").format(selfPatientDetails?.dob) : null)
            patientDetailsMap?.put("consentValue", consentObj?.extension?.getAt(0)?.valueString)
            patientDetailsMap?.put("consentObj", consentObj)
            patientDetailsMapList?.add(patientDetailsMap)
        }
        log.info("patientDetailsMapList for primary self patient - "+patientDetailsMapList)

        List selfPatientsList = resp?.get("selfPatients")?.get("list")
        selfPatientsList?.each {
            patientDetailsMap = [:]
            consentObj = getConsent(it?.patientDetails?.id?.toString())
            PatientResource patientResource = new PatientResource().getPersistedObject(it?.patientDetails?.id)
            for(obj in patientResource?.identifier){
                if(obj?.system?.value == "Governo Federal")
                {
                    pat_cpf = obj?.value?.value
                }
            }
            patientDetailsMap?.put("id", it?.patientDetails?.id)
            patientDetailsMap?.put("name", it?.patientDetails?.firstName)
            patientDetailsMap?.put("relationship", it?.patientDetails?.relationship)
            patientDetailsMap?.put("dob", (it?.patientDetails?.dob) ? new SimpleDateFormat("MM/dd/yyyy").format(it?.patientDetails?.dob) : null)
            patientDetailsMap?.put("consentValue", consentObj?.extension?.getAt(0)?.valueString)
            patientDetailsMap?.put("consentObj", consentObj)
            patientDetailsMapList?.add(patientDetailsMap)
        }
        log.info("patientDetailsMapList for secondary self patients is - "+patientDetailsMapList)

        String dateForCheck = new DateTime().minusYears(18)
        List dependentPatientsList = resp?.get("dependentPatients")?.get("list")
        dependentPatientsList?.each {
            if (it?.patientDetails && it?.patientDetails?.dob != null && (it?.patientDetails?.relationship == "Daughter" || it?.patientDetails?.relationship == "Son")) {
                if (dateForCheck < new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(it?.patientDetails?.dob)) {
                    consentObj = getConsent(it?.patientDetails?.id?.toString())
                    patientDetailsMap = [:]
                    PatientResource patientResource = new PatientResource().getPersistedObject(it?.patientDetails?.id)
                    for(obj in patientResource?.identifier){
                        if(obj?.system?.value == "Governo Federal")
                        {
                            pat_cpf = obj?.value?.value
                        }
                    }
                    patientDetailsMap?.put("id", it?.patientDetails?.id)
                    patientDetailsMap?.put("name", it?.patientDetails?.firstName)
                    patientDetailsMap?.put("relationship", it?.patientDetails?.relationship)
                    patientDetailsMap?.put("dob", (it?.patientDetails?.dob) ? new SimpleDateFormat("MM/dd/yyyy").format(it?.patientDetails?.dob) : null)
                    patientDetailsMap?.put("consentValue", consentObj?.extension?.getAt(0)?.valueString)
                    patientDetailsMap?.put("consentObj", consentObj)
                    patientDetailsMapList?.add(patientDetailsMap)

                }
            }
        }
        log.info("[consentForm()] patientDetailsMapList for all patient is - "+patientDetailsMapList)

        response['display'] = 'html-only'
        response['callbackUrl'] = "https://${System.getenv('PUBLIC_URL')}/minerva/widget/customWidget"
        response['formData'] = [
                actionContext : "updateConsentForm",
                consentOptions: ['everyone', 'noone'],
                patientDetails: patientDetailsMapList
        ]

        Map searchResultMap = mongoService.search('{"status":"Current"}', "consentText", '{"consentVersion":-1}', 10, 0)
        String staticConsentText = ""
        try{
            if(searchResultMap && searchResultMap.objects && searchResultMap.objects.size() > 0){
                staticConsentText = searchResultMap.objects.getAt(0).consentTermText
                consentVersionPickedFromCollection = searchResultMap.objects.getAt(0).consentVersion
                log.info("consentVersionPickedFromCollection - "+consentVersionPickedFromCollection)
            }
        }
        catch(exception){
            log.info("searchResultMap for consentText collection has empty objects");
        }

        if(patientDetailsMapList?.size() > 0) {

            String consentForHtml = ''' <div class="list_boxStyle mt15" ng-init="formData=$parent.deepCopy($parent.widget.widgetData.formData);">
                                <table aria-hidden="true" class="width_100pt tableHead mb10">
                                  <thead>
                                    <tr>
                                      <td class="tdFirst"><strong>{{'widget.patientDetails' | translate}}</strong> </td>
                                      <td class="vam w_150px"><strong>{{'widget.levelOfAccess' | translate}}</strong></td>
                                    </tr>
                                  </thead>
                                </table>
                                <ul role="list" aria-label="Patient Lists" id="mngPat_data_pLists">
                                  <li role="listitem" style="margin-top:-1px" class="listItem" id="mngPat_data_pList_" ng-repeat="patientDetail in formData.patientDetails">
                                    <table class="width_100pt">
                                      <tbody>
                                        <tr>
                                          <td>
                                            <div class="commentBox commentBox--thm-dmf-only">
                                              <div class="commentList widthImg50">
                                                <span aria-hidden="true" class="thm db pull-left flip" id="mngPat_data_pList__pThm">
                                                  <span class="noImgBg nameAvl">JD</span>
                                                </span>
                                                <div class="name pt5" id="mngPat_data_pList__pName">{{patientDetail.name}}</div>
                                                <div class="patDemo pt3" id="appPpAppSf2_bFp_patD">
                                                  <div class="dListCont inline">
                                                    <p class="dList" id="mngPat_data_pList__pDoL">
                                                      <i id="mngPat_data_pList__pDoLL" class="ng-binding">{{'widget.Born' | translate}}</i>
                                                      <span id="mngPat_data_pList__pDoLV">{{patientDetail.dob}}</span>
                                                    </p>
                                                    <p class="dList" id="mngPat_data_pList__pRel">
                                                      <i id="mngPat_data_pList__pRelL">{{'widget.relationship' | translate}}</i>
                                                      <span id="mngPat_data_pList__pRelV">{{patientDetail.relationship}}</span>
                                                    </p>
                                                  </div>
                                                </div>
                                                <div class="clearfix"></div>
                                              </div>
                                            </div>
                                          </td>
                                          <td class="vam w_150px text-right flip">
                                            <select class="form-control" data-ng-model="patientDetail.consentValue">
                                              <option ng-repeat="consent in formData.consentOptions" value="{{consent}}" ng-selected="consent==patientDetail.consentValue">{{'widget.consentForm.'+consent | translate}}</option>
                                            </select>
                                          </td>
                                        </tr>
                                      </tbody>
                                    </table>
                                  </li>
                                </ul>
                              </div>
                              <div class="bottomContainer">
                          <div class="btnContainer text-right">
                            <input type="button" class="btn btn-default btn-space" ng-click="formData=$parent.deepCopy($parent.widget.widgetData.formData)" value="{{('widget.Cancel' | translate)}}">
                            <input type="button" class="btn btn-primary btn-space" ng-click="$parent.submitWidgetForm($parent.widget.widgetData.callbackUrl, formData, true, true)" value="{{('widget.Submit' | translate)}}"}>
                          </div>
                        </div>'''
            consentText = staticConsentText+consentForHtml+'''<div class="widgetspacer"></div>'''
            response['data'] = consentText
        }
        else{
            String extraText = '''<div class="list_boxStyle mt15" ng-init="formData=$parent.deepCopy($parent.widget.widgetData.formData);">
                                    <table aria-hidden="true" class="width_100pt tableHead mb10">
                                      <thead>
                                        <tr>
                                          <td class="tdFirst">{{'widget.patientDetails' | translate}}</td>
                                          <td class="vam w_150px"> {{'widget.levelOfAccess' | translate}} </td>
                                        </tr>
                                      </thead>
                                    </table>
                                  </div>
                                <div class="bottomContainer">
                                 <div>
                                     <p class ="text-center" >Please link either your self or child's (son/daughter) clinical<br>record to manage the data access preferences.</p>
                                  </div>
                                 </div>'''
            consentText = '''<div class="contentBlock">'''+staticConsentText +'''</div>'''+ extraText
            response['data'] = consentText
        }
        return response
    }

    Map updateConsentForm(final ChainExecutionContext chainExecutionContext) {
        Map jsonObject = chainExecutionContext.request
        Map response = chainExecutionContext.response
        Map data = chainExecutionContext.data

        log.info("[updateConsentForm()] jsonObject : "+jsonObject)

        jsonObject?.patientDetails?.each {
            JSONObject consentObj = it?.consentObj
            if(consentObj?.extension?.getAt(0)?.valueString != it?.consentValue) {
                consentObj?.extension?.getAt(0)?.valueString = it?.consentValue
                log.info("Patient Cpf - "+pat_cpf)
                if(consentObj?.identifier?.value){
                    consentObj?.identifier?.value = pat_cpf+"_"+consentVersionPickedFromCollection
                }
                log.info("consent obj identifier - "+consentObj.identifier)
                log.info("consent obj - "+consentObj)
                String restUrl = "https://${System.getenv('PUBLIC_URL')}/minerva/fhir/stu3/Consent/" + consentObj?.id
                def resp = rest.put(restUrl) {
                    header "x-auth-token", config?.token
                    contentType "application/json"
                    json consentObj.encodeAsJSON()
                }
                if (resp?.json?.id) {
                    log.info("[updateConsentForm() ] : Consent with id ${consentObj?.id} is updated successfully")
                } else {
                    log.error("[updateConsentForm() ] : Consent with id ${consentObj?.id} is not updated")
                }
            }
            else{
                log.info("[updateConsentForm() ] : Consent with id ${consentObj?.id} has same updated value so not updating")
            }
        }
        response['display'] = 'html-only'
        response['callbackUrl'] = "https://${System.getenv('PUBLIC_URL')}/minerva/widget/customWidget"
        response['formData'] = jsonObject
        response['data'] = consentText
        return response
    }

    JSONObject getConsent(String patId){
        String restUrl = "https://${System.getenv('PUBLIC_URL')}/minerva/fhir/stu3/Consent?patient=" + patId
        log.info("consent url - "+restUrl)
        def resp = rest.get(restUrl) {
            header "x-auth-token", config?.token
            contentType "application/json"
        }
        log.info("consent obj resp from resp - "+resp)
        JSONObject consentObj = resp?.json?.entry?.find {
            it?.resource?.category?.getAt(0)?.text == "Patient Consent" && it?.resource?.extension?.getAt(0)?.valueString in ["everyone","noone"] && it?.resource?.status == "active"
        }?.resource
        log.info("consent onj query to search consent value - "+consentObj)
        return consentObj;
    }
}
