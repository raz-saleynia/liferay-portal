<#include "../init.ftl">
<#assign ddlRecordSetLocalService = serviceLocator.findService("com.liferay.portlet.dynamicdatalists.service.DDLRecordSetLocalService")>
<#assign ddlRecordLocalService = serviceLocator.findService("com.liferay.portlet.dynamicdatalists.service.DDLRecordLocalService")>
<#assign ddmStorageLinkLocalService = serviceLocator.findService("com.liferay.portlet.dynamicdatamapping.service.DDMStorageLinkLocalService")>
<#if required>
	<#assign label = label + " (" + languageUtil.get(locale, "required") + ")">
</#if>

<#assign ddlRecordSetsCount = ddlRecordSetLocalService.getDDLRecordSetsCount()>
<#assign ddlRecordSets = ddlRecordSetLocalService.getRecordSets(scopeGroupId)>
<#list ddlRecordSets as ddlRecordSet>
<#assign ddlRecordSetName = ddlRecordSet.getName(languageUtil.getLanguageId(request))>
<#if predefinedValue == ddlRecordSetName >
	 <@aui.select cssClass=cssClass name=namespacedFieldName label=label>
			<#assign fieldsMap= ddlRecordSet.getDDMStructure().getFieldsMap()>
			 <#assign ddlRecords = ddlRecordLocalService.getRecords(ddlRecordSet.getRecordSetId())>
			 	<#list ddlRecords as ddlRecord>
			 			<#assign selected = (fieldRawValue ==  ddlRecord.getRecordId()?c?string)>
						<#assign ddlRecordVersion = ddlRecord.getRecordVersion()>
						<#assign fieldsModel = storageEngine.getFields(ddlRecordVersion.getDDMStorageId())>
						 <#assign fieldsMapKeys = fieldsMap?keys>
							<#list fieldsMapKeys as key>
							   <#list fieldsMap[key]?keys as key2>
								<#if key2 == 'name'>
								   <#assign ddlNameField = fieldsMap[key][key2]>
								</#if>
								</#list>
							</#list>
							<#assign namesList = fieldsModel.getNames()>
							<#list namesList as modelFieldName>
								<#if fieldsModel.get(modelFieldName).getName() == ddlNameField>
									 <#assign ddlValue =  fieldsModel.get(modelFieldName).getValue()>
									<@aui.option label=ddlValue  value=ddlRecord.getRecordId() selected=selected/>
								</#if>
							</#list>	
							
							
				</#list>
	</@aui.select>
	<#break>
</#if>			
</#list>			




