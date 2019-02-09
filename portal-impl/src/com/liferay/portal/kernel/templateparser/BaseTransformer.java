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

import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.*;
import com.liferay.portal.kernel.xml.Document;
import com.liferay.portal.theme.ThemeDisplay;
import com.liferay.portal.util.PortalUtil;
import org.springframework.util.StopWatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Brian Wing Shun Chan
 * @author Raymond Augé
 * @author Wesley Gong
 * @author Angelo Jefferson
 * @author Hugo Huijser
 * @author Marcellus Tavares
 */
public abstract class BaseTransformer implements Transformer {
	public String transform(
			ThemeDisplay themeDisplay, Map<String, String> tokens,
			String viewMode, String languageId, String xml, Document document, String script,
			String langType)
		throws Exception {

		// Setup Listeners

		if (_log.isDebugEnabled()) {
			_log.debug("Language " + languageId);
		}

		if (Validator.isNull(viewMode)) {
			viewMode = Constants.VIEW;
		}

		if (_logTokens.isDebugEnabled()) {
			String tokensString = PropertiesUtil.list(tokens);

			_logTokens.debug(tokensString);
		}

		if (_logTransformBefore.isDebugEnabled()) {
			_logTransformBefore.debug(xml);
		}

		List<ExtTransformerListener> listenersList =
			new ArrayList<ExtTransformerListener>();

		String[] listeners = getTransformerListenersClassNames();

		for (int i = 0; i < listeners.length; i++) {
			ExtTransformerListener listener = null;

			try {
				if (_log.isDebugEnabled()) {
					_log.debug("Instantiate listener " + listeners[i]);
				}

				boolean templateDriven = Validator.isNotNull(langType);

				listener = null;
				
				Class<?> cachedClass = portalClassCache.get(listeners[i]);
				
				if (cachedClass != null) {
					listener = (ExtTransformerListener)cachedClass.newInstance();
				}
				else {
					ClassLoader classLoader =
						PortalClassLoaderUtil.getClassLoader();
					
					listener = (ExtTransformerListener)InstanceFactory.newInstance(classLoader, listeners[i]);
				}
				
				listener.setTemplateDriven(templateDriven);
				listener.setLanguageId(languageId);
				listener.setTokens(tokens);

				listenersList.add(listener);
			}
			catch (Exception e) {
				_log.error(e, e);
			}

			// Modify XML

			if (_logXmlBeforeListener.isDebugEnabled()) {
				_logXmlBeforeListener.debug(xml);
			}

			if (listener != null) {
				//xml = listener.onXml(xml);
				document = listener.onXml(document);

				if (_logXmlAfterListener.isDebugEnabled()) {
					_logXmlAfterListener.debug(xml);
				}
			}

			// Modify script

			if (_logScriptBeforeListener.isDebugEnabled()) {
				_logScriptBeforeListener.debug(script);
			}

			if (listener != null) {
				listener.setDocument(document);
				script = listener.onScript(script);

				if (_logScriptAfterListener.isDebugEnabled()) {
					_logScriptAfterListener.debug(script);
				}
			}
		}

		// Transform

		String output = null;

		if (Validator.isNull(langType)) {
			output = LocalizationUtil.getLocalization(xml, languageId);
		}
		else {
			String templateParserClassName = getTemplateParserClassName(
				langType);

			if (_log.isDebugEnabled()) {
				_log.debug(
					"Template parser class name " + templateParserClassName);
			}

			if (Validator.isNotNull(templateParserClassName)) {
				TemplateParser templateParser = null;

				// EMDA-6233 reduce WebappClassLoader contention
				if (templateParserClassName.equals("com.endplay.portlet.journal.util.VelocityTemplateParser")) {
					templateParser = new com.endplay.portlet.journal.util.VelocityTemplateParser();
				}
				else {
					try {
						templateParser =
							(TemplateParser)InstanceFactory.newInstance(
								PortalClassLoaderUtil.getClassLoader(),
								templateParserClassName);
					}
					catch (Exception e) {
						throw new TransformException(e);
					}
				}

				templateParser.setLanguageId(languageId);
				templateParser.setScript(script);
				templateParser.setThemeDisplay(themeDisplay);
				templateParser.setTokens(tokens);
				templateParser.setViewMode(viewMode);
				templateParser.setXML(xml);
				templateParser.setDocument(document);

				// ***************************************
				// EXT Override Start: Charles Im
				// ***************************************
				
				if (_logEPPerf.isInfoEnabled()) {
					StopWatch s = new StopWatch("BaseTransformer.transform()");
					s.start(getLogTimeString(themeDisplay, "BaseTransformer.transform().templateParser.transform()", tokens));
					output = templateParser.transform();
					s.stop();
					_logEPPerf.info(s.getLastTaskName() + " [millis]: " + s.getLastTaskTimeMillis());
				} else {
					output = templateParser.transform();
				}
				// ***************************************
				// EXT Override End
				// ***************************************
			}
		}

		// Postprocess output
		for (int i = 0; i < listenersList.size(); i++) {
			ExtTransformerListener listener = listenersList.get(i);

			// Modify output

			if (_logOutputBeforeListener.isDebugEnabled()) {
				_logOutputBeforeListener.debug(output);
			}

			output = listener.onOutput(output);

			if (_logOutputAfterListener.isDebugEnabled()) {
				_logOutputAfterListener.debug(output);
			}
		}

		if (_logTransfromAfter.isDebugEnabled()) {
			_logTransfromAfter.debug(output);
		}
		
		return output;
	}
	public String transform(
			ThemeDisplay themeDisplay, Map<String, String> tokens,
			String viewMode, String languageId, String xml, String script,
			String langType)
		throws Exception {

		// Setup Listeners

		if (_log.isDebugEnabled()) {
			_log.debug("Language " + languageId);
		}

		if (Validator.isNull(viewMode)) {
			viewMode = Constants.VIEW;
		}

		if (_logTokens.isDebugEnabled()) {
			String tokensString = PropertiesUtil.list(tokens);

			_logTokens.debug(tokensString);
		}

		if (_logTransformBefore.isDebugEnabled()) {
			_logTransformBefore.debug(xml);
		}

		List<TransformerListener> listenersList =
			new ArrayList<TransformerListener>();

		String[] listeners = getTransformerListenersClassNames();

		for (int i = 0; i < listeners.length; i++) {
			TransformerListener listener = null;

			try {
				if (_log.isDebugEnabled()) {
					_log.debug("Instantiate listener " + listeners[i]);
				}

				boolean templateDriven = Validator.isNotNull(langType);

				ClassLoader classLoader =
					PortalClassLoaderUtil.getClassLoader();

				listener = (TransformerListener)InstanceFactory.newInstance(
					classLoader, listeners[i]);

				listener.setTemplateDriven(templateDriven);
				listener.setLanguageId(languageId);
				listener.setTokens(tokens);

				listenersList.add(listener);
			}
			catch (Exception e) {
				_log.error(e, e);
			}

			// Modify XML

			if (_logXmlBeforeListener.isDebugEnabled()) {
				_logXmlBeforeListener.debug(xml);
			}

			if (listener != null) {
				xml = listener.onXml(xml);

				if (_logXmlAfterListener.isDebugEnabled()) {
					_logXmlAfterListener.debug(xml);
				}
			}

			// Modify script

			if (_logScriptBeforeListener.isDebugEnabled()) {
				_logScriptBeforeListener.debug(script);
			}

			if (listener != null) {
				script = listener.onScript(script);

				if (_logScriptAfterListener.isDebugEnabled()) {
					_logScriptAfterListener.debug(script);
				}
			}
		}

		// Transform

		String output = null;

		if (Validator.isNull(langType)) {
			output = LocalizationUtil.getLocalization(xml, languageId);
		}
		else {
			String templateParserClassName = getTemplateParserClassName(
				langType);

			if (_log.isDebugEnabled()) {
				_log.debug(
					"Template parser class name " + templateParserClassName);
			}

			if (Validator.isNotNull(templateParserClassName)) {
				TemplateParser templateParser = null;

				try {
					templateParser =
						(TemplateParser)InstanceFactory.newInstance(
							PortalClassLoaderUtil.getClassLoader(),
							templateParserClassName);
				}
				catch (Exception e) {
					throw new TransformException(e);
				}

				templateParser.setLanguageId(languageId);
				templateParser.setScript(script);
				templateParser.setThemeDisplay(themeDisplay);
				templateParser.setTokens(tokens);
				templateParser.setViewMode(viewMode);
				templateParser.setXML(xml);

				// ***************************************
				// EXT Override Start: Charles Im
				// ***************************************
				
				if (_logEPPerf.isInfoEnabled()) {
					StopWatch s = new StopWatch("BaseTransformer.transform()");
					s.start(getLogTimeString(themeDisplay, "BaseTransformer.transform().templateParser.transform()", tokens));
					output = templateParser.transform();
					s.stop();
					_logEPPerf.info(s.getLastTaskName() + " [millis]: " + s.getLastTaskTimeMillis());
				} else {
					output = templateParser.transform();
				}
				
				// ***************************************
				// EXT Override End
				// ***************************************
			}
		}

		// Postprocess output

		for (int i = 0; i < listenersList.size(); i++) {
			TransformerListener listener = listenersList.get(i);

			// Modify output

			if (_logOutputBeforeListener.isDebugEnabled()) {
				_logOutputBeforeListener.debug(output);
			}

			output = listener.onOutput(output);

			if (_logOutputAfterListener.isDebugEnabled()) {
				_logOutputAfterListener.debug(output);
			}
		}

		if (_logTransfromAfter.isDebugEnabled()) {
			_logTransfromAfter.debug(output);
		}

		return output;
	}

	// ***************************************
	// EXT Override Start: Charles Im
	// ***************************************
	
	protected String getLogTimeString(ThemeDisplay themeDisplay, String msg, Map<String, String> tokens) throws Exception {
		StringBuilder buf = new StringBuilder();
		buf.append(msg);
		buf.append(" ");
		
		String friendlyUrl = "";
		if ((themeDisplay != null) && (themeDisplay.getLayout() != null)) {
			friendlyUrl = PortalUtil.getLayoutURL(themeDisplay.getLayout(), themeDisplay);
		}
		
		if ((tokens != null) && (tokens.size() > 0)) {
			//buf.append("articleid [%s] ");
			//buf.append("version [%s] ");
			//buf.append("title [%s] ");
			//buf.append("urltitle [%s] ");
			//buf.append("type [%s] ");
			buf.append("groupurl [%s] ");
			buf.append("groupid [%s] ");
			buf.append("structureid [%s] ");
			buf.append("templateid [%s] ");
			buf.append("friendlyurl [%s] ");
		}
		
		if ((tokens != null) && (tokens.size() > 0)) {
			return String.format(buf.toString(), 
					//tokens.get("reserved-article-id"), 
					//tokens.get("reserved-article-version"), 
					//tokens.get("reserved-article-title"), 
					//tokens.get("reserved-article-url-title"), 
					//tokens.get("reserved-article-type"), 
					tokens.get("group_friendly_url"), 
					tokens.get("group_id"), 
					tokens.get("structure_id"), 
					tokens.get("template_id"),
					friendlyUrl);
		} else {
			return String.format(buf.toString());
		}
	}
	
	// ***************************************
	// EXT Override End
	// ***************************************

	protected abstract String getTemplateParserClassName(String langType);

	protected abstract String[] getTransformerListenersClassNames();

	private static Log _log = LogFactoryUtil.getLog(BaseTransformer.class);

	private static Log _logOutputAfterListener = LogFactoryUtil.getLog(
		BaseTransformer.class.getName() + ".OutputAfterListener");
	private static Log _logOutputBeforeListener = LogFactoryUtil.getLog(
		BaseTransformer.class.getName() + ".OutputBeforeListener");
	private static Log _logScriptAfterListener = LogFactoryUtil.getLog(
		BaseTransformer.class.getName() + ".ScriptAfterListener");
	private static Log _logScriptBeforeListener = LogFactoryUtil.getLog(
		BaseTransformer.class.getName() + ".ScriptBeforeListener");
	private static Log _logTokens = LogFactoryUtil.getLog(
		BaseTransformer.class.getName() + ".Tokens");
	private static Log _logTransformBefore = LogFactoryUtil.getLog(
		BaseTransformer.class.getName() + ".TransformBefore");
	private static Log _logTransfromAfter = LogFactoryUtil.getLog(
		BaseTransformer.class.getName() + ".TransformAfter");
	private static Log _logXmlAfterListener = LogFactoryUtil.getLog(
		BaseTransformer.class.getName() + ".XmlAfterListener");
	private static Log _logXmlBeforeListener = LogFactoryUtil.getLog(
		BaseTransformer.class.getName() + ".XmlBeforeListener");

	// ***************************************
	// EXT Override Start: Charles Im
	// ***************************************
	
	private static final Log _logEPPerf = LogFactoryUtil.getLog("ep.time.log." + BaseTransformer.class.getName());
	
	private static Map<String, Class<?>> portalClassCache = new HashMap<String, Class<?>>();
	
	static {
		ClassLoader classLoader = PortalClassLoaderUtil.getClassLoader();
		
		String[] listeners = { "com.liferay.portlet.journal.util.TokensTransformerListener", "com.liferay.portlet.journal.util.ContentTransformerListener", "com.liferay.portlet.journal.util.LocaleTransformerListener",
				"com.liferay.portlet.journal.util.RegexTransformerListener", "com.liferay.portlet.journal.util.ViewCounterTransformerListener" };

		for (String listener : listeners) {
			try {
				Class<?> clazz = classLoader.loadClass(listener);
				
				portalClassCache.put(listener, clazz);
			}
			catch (Exception ex) {
				_log.error(ex);
			}
		}
	}
	
	
	// ***************************************
	// EXT Override End
	// ***************************************
}