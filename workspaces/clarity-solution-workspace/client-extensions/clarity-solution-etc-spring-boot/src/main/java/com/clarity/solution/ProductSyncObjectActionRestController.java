/**
 * SPDX-FileCopyrightText: (c) 2025 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.clarity.solution;

import com.liferay.client.extension.util.spring.boot3.BaseRestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import java.time.Instant;

import java.util.Base64;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;

import org.springframework.beans.factory.annotation.Value;
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

		String runId = UUID.randomUUID().toString();

		_appendDemoLog(runId, "request.payload", json);

		JSONObject syncRequestJSONObject = _getSyncRequestJSONObject(
			new JSONObject(json));

		String authorization = _getAuthorization();

		_patchSyncRequest(
			authorization, syncRequestJSONObject,
			new JSONObject(
			).put(
				"syncStatus", "running"
			));

		String batchExternalReferenceCode = _postProductImportTask(
			authorization,
			_toProductsJSONArray(
				_getCatalogExternalReferenceCode(authorization)));

		JSONObject importTaskStatusJSONObject =
			_getImportTaskByExternalReferenceCodeJSONObject(
				authorization, batchExternalReferenceCode);

		JSONObject valuesJSONObject = new JSONObject(
		).put(
			"batchExternalReferenceCode", batchExternalReferenceCode
		).put(
			"syncStatus", "submitted"
		);

		if (importTaskStatusJSONObject != null) {
			JSONArray failedItemsJSONArray =
				importTaskStatusJSONObject.optJSONArray("failedItems");

			valuesJSONObject.put(
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
				"syncStatus",
				importTaskStatusJSONObject.optString("executeStatus")
			);
		}

		_patchSyncRequest(
			authorization, syncRequestJSONObject, valuesJSONObject);

		_appendDemoLog(runId, "result.values", valuesJSONObject.toString(2));

		return new ResponseEntity<>(HttpStatus.OK);
	}

	private void _appendDemoLog(String runId, String stage, String payload) {
		String line = String.format(
			"%s | run=%s | stage=%s%n%s%n%s%n",
			Instant.now(), runId, stage, payload,
			"----------------------------------------");

		Path path = Paths.get(_demoLogPath);

		try {
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}

			Files.writeString(
				path, line, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		catch (IOException ioException) {
			System.err.println(
				"Unable to write demo log: " + ioException.getMessage());
		}
	}

	private String _getAuthorization() {
		String encodedBasicAuthorization = Base64.getEncoder(
		).encodeToString(
			_basicAuthorization.getBytes(StandardCharsets.UTF_8)
		);

		return "Basic " + encodedBasicAuthorization;
	}

	private String _getCatalogExternalReferenceCode(String authorization) {
		JSONObject catalogsJSONObject = new JSONObject(
			get(
				authorization,
				UriComponentsBuilder.fromPath(
					"/o/headless-commerce-admin-catalog/v1.0/catalogs"
				).build(
				).toUri()));

		JSONArray itemsJSONArray = catalogsJSONObject.getJSONArray("items");

		if (itemsJSONArray.isEmpty()) {
			throw new IllegalStateException(
				"Unable to start product sync without a Commerce catalog");
		}

		return itemsJSONArray.getJSONObject(
			0
		).getString(
			"externalReferenceCode"
		);
	}

	private JSONObject _getImportTaskByExternalReferenceCodeJSONObject(
			String authorization, String batchExternalReferenceCode)
		throws Exception {

		for (int i = 0; i < 2; i++) {
			String importTask = get(
				authorization,
				UriComponentsBuilder.fromPath(
					"/o/headless-batch-engine/v1.0/import-task" +
						"/by-external-reference-code/" +
							batchExternalReferenceCode
				).build(
				).toUri());

			if (importTask == null) {
				Thread.sleep(500);

				continue;
			}

			JSONObject importTaskJSONObject = new JSONObject(importTask);

			String executeStatus = importTaskJSONObject.optString(
				"executeStatus");

			if (!executeStatus.equals("STARTED")) {
				return importTaskJSONObject;
			}

			Thread.sleep(500);
		}

		return null;
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

	private String _postProductImportTask(
		String authorization, JSONArray productsJSONArray) {

		String batchExternalReferenceCode =
			"CLARITY_PRODUCT_SYNC_" + UUID.randomUUID();

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
			).toUri());

		return batchExternalReferenceCode;
	}

	private JSONArray _toProductsJSONArray(String catalogExternalReferenceCode)
		throws Exception {

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
						"catalogExternalReferenceCode",
						catalogExternalReferenceCode
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

	@Value("${liferay.demo.basic.authorization:test@liferay.com:test}")
	private String _basicAuthorization;

	@Value("${clarity.demo.log.path:/tmp/clarity-sync-demo.log}")
	private String _demoLogPath;

}