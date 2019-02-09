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
public interface ExtTransformerListener {

	public String getLanguageId();

	public Map<String, String> getTokens();

	public boolean isTemplateDriven();

	public String onOutput(String s);

	public String onScript(String s);

	public String onXml(String s);
	
	public Document onXml(Document s);

	public void setLanguageId(String languageId);

	public void setTemplateDriven(boolean templateDriven);

	public void setTokens(Map<String, String> tokens);
	
	public Document getDocument();

	public void setDocument(Document document);

}