/**
 * Copyright (c) 2000-2012 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.portal.kernel.templateparser;

import com.liferay.portal.kernel.xml.Document;

import java.util.Map;

/**
 * @author Brian Wing Shun Chan
 */
public abstract class ExtBaseTransformerListener implements ExtTransformerListener {

	public String getLanguageId() {
		return _languageId;
	}

	public Map<String, String> getTokens() {
		return _tokens;
	}

	public boolean isTemplateDriven() {
		return _templateDriven;
	}

	public abstract String onOutput(String s);

	public abstract String onScript(String s);

	public abstract String onXml(String s);
	
	public Document onXml(Document document) {
		return document;
	}

	public void setLanguageId(String languageId) {
		_languageId = languageId;
	}

	public void setTemplateDriven(boolean templateDriven) {
		_templateDriven = templateDriven;
	}

	public void setTokens(Map<String, String> tokens) {
		_tokens = tokens;
	}
	
	public Document getDocument() {
		return document;
	}

	public void setDocument(Document document) {
		this.document = document;
	}

	private String _languageId;
	private boolean _templateDriven;
	private Map<String, String> _tokens;
	private Document document;


}