/**
 * SPDX-FileCopyrightText: (c) 2025 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.clarity.solution;

import com.liferay.client.extension.util.spring.boot3.BaseRestController;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import java.nio.charset.StandardCharsets;

import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * @author Liferay
 */
@RequestMapping("/object/action/product-sync")
@RestController
public class ProductSyncObjectActionRestController extends BaseRestController {

	@PostMapping
	public ResponseEntity<String> post(
			@AuthenticationPrincipal Jwt jwt, @RequestBody String json)
		throws Exception {

		JSONObject syncRequestJSONObject = _getSyncRequestJSONObject(
			new JSONObject(json));

		_patchSyncRequest(
			jwt.toString(), syncRequestJSONObject,
			new JSONObject(
			).put(
				"status", "running"
			));

		JSONObject importTaskJSONObject = _postProductImportTask(
			jwt.toString(), _toProductsJSONArray());

		String batchExternalReferenceCode = importTaskJSONObject.getString(
			"externalReferenceCode");

		JSONObject importTaskStatusJSONObject =
			_getImportTaskByExternalReferenceCodeJSONObject(
				jwt.toString(), batchExternalReferenceCode);

		JSONArray failedItemsJSONArray =
			importTaskStatusJSONObject.optJSONArray("failedItems");

		_patchSyncRequest(
			jwt.toString(), syncRequestJSONObject,
			new JSONObject(
			).put(
				"batchExternalReferenceCode", batchExternalReferenceCode
			).put(
				"errorMessage",
				importTaskStatusJSONObject.optString("errorMessage")
			).put(
				"failedItemsCount",
				(failedItemsJSONArray == null) ? 0 :
					failedItemsJSONArray.length()
			).put(
				"processedItemsCount",
				importTaskStatusJSONObject.optInt("processedItemsCount")
			).put(
				"status", importTaskStatusJSONObject.optString("executeStatus")
			));

		return new ResponseEntity<>(HttpStatus.OK);
	}

	private JSONObject _getImportTaskByExternalReferenceCodeJSONObject(
			String authorization, String batchExternalReferenceCode)
		throws Exception {

		for (int i = 0; i < 5; i++) {
			JSONObject importTaskJSONObject = new JSONObject(
				get(
					authorization,
					UriComponentsBuilder.fromPath(
						"/o/headless-batch-engine/v1.0/import-task" +
							"/by-external-reference-code/" +
								batchExternalReferenceCode
					).build(
					).toUri()));

			String executeStatus = importTaskJSONObject.optString(
				"executeStatus");

			if (!executeStatus.equals("STARTED")) {
				return importTaskJSONObject;
			}

			Thread.sleep(2000);
		}

		return new JSONObject(
			get(
				authorization,
				UriComponentsBuilder.fromPath(
					"/o/headless-batch-engine/v1.0/import-task" +
						"/by-external-reference-code/" +
							batchExternalReferenceCode
				).build(
				).toUri()));
	}

	private JSONObject _getSyncRequestJSONObject(JSONObject payloadJSONObject) {
		String key = "objectEntryDTOClaritySyncRequest";

		if (payloadJSONObject.has(key)) {
			return payloadJSONObject.getJSONObject(key);
		}

		for (String curKey : payloadJSONObject.keySet()) {
			if (curKey.startsWith("objectEntryDTO")) {
				return payloadJSONObject.getJSONObject(curKey);
			}
		}

		throw new IllegalArgumentException(
			"Object action payload does not include a sync request object " +
				"entry");
	}

	private void _patchSyncRequest(
		String authorization, JSONObject syncRequestJSONObject,
		JSONObject valuesJSONObject) {

		String externalReferenceCode = syncRequestJSONObject.optString(
			"externalReferenceCode");

		String path = "/o/c/claritysyncrequests/";

		if (externalReferenceCode.isEmpty()) {
			path += syncRequestJSONObject.getLong("id");
		}
		else {
			path += "by-external-reference-code/" + externalReferenceCode;
		}

		patch(
			authorization, valuesJSONObject.toString(),
			UriComponentsBuilder.fromPath(
				path
			).build(
			).toUri());
	}

	private JSONObject _postProductImportTask(
		String authorization, JSONArray productsJSONArray) {

		String batchExternalReferenceCode =
			"CLARITY_PRODUCT_SYNC_" + UUID.randomUUID();

		return new JSONObject(
			post(
				authorization, productsJSONArray.toString(),
				UriComponentsBuilder.fromPath(
					"/o/headless-batch-engine/v1.0/import-task/" +
						_CLASS_NAME_PRODUCT
				).queryParam(
					"batchExternalReferenceCode", batchExternalReferenceCode
				).queryParam(
					"createStrategy", "UPSERT"
				).queryParam(
					"externalReferenceCode", batchExternalReferenceCode
				).queryParam(
					"importStrategy", "ON_ERROR_CONTINUE"
				).build(
				).toUri()));
	}

	private JSONArray _toProductsJSONArray() throws Exception {
		JSONArray productsJSONArray = new JSONArray();

		InputStream inputStream = getClass().getResourceAsStream(
			"/products.csv");

		try (BufferedReader bufferedReader = new BufferedReader(
				new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

			String header = bufferedReader.readLine();

			if (header == null) {
				return productsJSONArray;
			}

			String line = null;

			while ((line = bufferedReader.readLine()) != null) {
				String[] values = line.split(",", -1);

				productsJSONArray.put(
					new JSONObject(
					).put(
						"active", true
					).put(
						"catalogExternalReferenceCode", values[3]
					).put(
						"externalReferenceCode", values[0]
					).put(
						"name",
						new JSONObject(
						).put(
							"en_US", values[1]
						)
					).put(
						"productType", values[2]
					).put(
						"shortDescription",
						new JSONObject(
						).put(
							"en_US", values[4]
						)
					));
			}
		}

		return productsJSONArray;
	}

	private static final String _CLASS_NAME_PRODUCT =
		"com.liferay.headless.commerce.admin.catalog.dto.v1_0.Product";

}