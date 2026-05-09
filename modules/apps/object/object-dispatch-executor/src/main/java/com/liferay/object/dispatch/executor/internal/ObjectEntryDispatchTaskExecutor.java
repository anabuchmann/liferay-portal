/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.object.dispatch.executor.internal;

import com.liferay.dispatch.executor.BaseDispatchTaskExecutor;
import com.liferay.dispatch.executor.DispatchTaskExecutor;
import com.liferay.dispatch.executor.DispatchTaskExecutorOutput;
import com.liferay.dispatch.model.DispatchTrigger;
import com.liferay.object.constants.ObjectEntryFolderConstants;
import com.liferay.object.model.ObjectDefinition;
import com.liferay.object.model.ObjectEntry;
import com.liferay.object.service.ObjectDefinitionLocalService;
import com.liferay.object.service.ObjectEntryLocalService;
import com.liferay.petra.string.StringBundler;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.json.JSONFactory;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.UnicodeProperties;
import com.liferay.portal.kernel.util.Validator;

import java.io.Serializable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * @author Liferay
 */
@Component(
	property = {
		"dispatch.task.executor.name=" + ObjectEntryDispatchTaskExecutor.KEY,
		"dispatch.task.executor.type=" + ObjectEntryDispatchTaskExecutor.TYPE
	},
	service = DispatchTaskExecutor.class
)
public class ObjectEntryDispatchTaskExecutor extends BaseDispatchTaskExecutor {

	public static final String KEY = "object-entry-trigger";

	public static final String TYPE = "object-entry-trigger";

	@Override
	public void doExecute(
			DispatchTrigger dispatchTrigger,
			DispatchTaskExecutorOutput dispatchTaskExecutorOutput)
		throws Exception {

		UnicodeProperties dispatchTaskSettingsUnicodeProperties =
			dispatchTrigger.getDispatchTaskSettingsUnicodeProperties();

		String operation = dispatchTaskSettingsUnicodeProperties.getProperty(
			"operation", "add");

		if (!operation.equals("add")) {
			throw new PortalException(
				"Unsupported object entry dispatch operation " + operation);
		}

		ObjectDefinition objectDefinition = _getObjectDefinition(
			dispatchTrigger.getCompanyId(),
			dispatchTaskSettingsUnicodeProperties);

		ObjectEntry objectEntry = _objectEntryLocalService.addObjectEntry(
			GetterUtil.getLong(
				dispatchTaskSettingsUnicodeProperties.getProperty(
					"scopeGroupId")),
			dispatchTrigger.getUserId(),
			objectDefinition.getObjectDefinitionId(),
			ObjectEntryFolderConstants.PARENT_OBJECT_ENTRY_FOLDER_ID_DEFAULT,
			null,
			_toValues(
				dispatchTaskSettingsUnicodeProperties.getProperty("values")),
			_getServiceContext(
				dispatchTrigger, dispatchTaskSettingsUnicodeProperties));

		dispatchTaskExecutorOutput.setOutput(
			StringBundler.concat(
				"Created object entry ", objectEntry.getObjectEntryId(),
				" for object definition ", objectDefinition.getName()));
	}

	@Override
	public String getName() {
		return KEY;
	}

	private ObjectDefinition _getObjectDefinition(
			long companyId,
			UnicodeProperties dispatchTaskSettingsUnicodeProperties)
		throws Exception {

		String objectDefinitionExternalReferenceCode =
			dispatchTaskSettingsUnicodeProperties.getProperty(
				"objectDefinitionExternalReferenceCode");

		if (Validator.isNotNull(objectDefinitionExternalReferenceCode)) {
			ObjectDefinition objectDefinition =
				_objectDefinitionLocalService.
					fetchObjectDefinitionByExternalReferenceCode(
						objectDefinitionExternalReferenceCode, companyId);

			if (objectDefinition != null) {
				return objectDefinition;
			}
		}

		String objectDefinitionName =
			dispatchTaskSettingsUnicodeProperties.getProperty(
				"objectDefinitionName");

		if (Validator.isNotNull(objectDefinitionName)) {
			ObjectDefinition objectDefinition =
				_objectDefinitionLocalService.fetchObjectDefinition(
					companyId, objectDefinitionName);

			if (objectDefinition != null) {
				return objectDefinition;
			}
		}

		throw new PortalException(
			"Unable to resolve object definition from dispatch settings");
	}

	private ServiceContext _getServiceContext(
		DispatchTrigger dispatchTrigger,
		UnicodeProperties dispatchTaskSettingsUnicodeProperties) {

		ServiceContext serviceContext = new ServiceContext();

		serviceContext.setCompanyId(dispatchTrigger.getCompanyId());
		serviceContext.setScopeGroupId(
			GetterUtil.getLong(
				dispatchTaskSettingsUnicodeProperties.getProperty(
					"scopeGroupId")));
		serviceContext.setUserId(dispatchTrigger.getUserId());

		return serviceContext;
	}

	private Map<String, Serializable> _toValues(String values)
		throws Exception {

		Map<String, Serializable> valuesMap = new HashMap<>();

		if (Validator.isNull(values)) {
			return valuesMap;
		}

		JSONObject jsonObject = _jsonFactory.createJSONObject(values);

		Iterator<String> iterator = jsonObject.keys();

		while (iterator.hasNext()) {
			String key = iterator.next();

			Object value = jsonObject.get(key);

			if (value instanceof Serializable) {
				valuesMap.put(key, (Serializable)value);
			}
			else if (value != null) {
				valuesMap.put(key, String.valueOf(value));
			}
		}

		return valuesMap;
	}

	@Reference
	private JSONFactory _jsonFactory;

	@Reference
	private ObjectDefinitionLocalService _objectDefinitionLocalService;

	@Reference
	private ObjectEntryLocalService _objectEntryLocalService;

}