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

package com.liferay.portal.captcha.recaptcha;

import com.liferay.portal.captcha.simplecaptcha.SimpleCaptchaImpl;
import com.liferay.portal.kernel.captcha.CaptchaException;
import com.liferay.portal.kernel.captcha.CaptchaTextException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.PropsKeys;
import com.liferay.portal.util.PortalUtil;
import com.liferay.portal.util.PrefsPropsUtil;
import com.liferay.portal.util.PropsValues;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import javax.portlet.PortletRequest;
import javax.portlet.PortletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;

/**
 * @author Tagnaouti Boubker
 * @author Jorge Ferrer
 * @author Brian Wing Shun Chan
 * @author Daniel Sanz
 */
public class ReCaptchaImpl extends SimpleCaptchaImpl {

    private static final String SECRET_PARAM = "secret";
    private static final String RESPONSE_PARAM = "response";
    private static final String G_RECAPTCHA_RESPONSE = "g-recaptcha-response";

    @Override
    public String getTaglibPath() {
        return _TAGLIB_PATH;
    }

    @Override
    public void serveImage(
            HttpServletRequest request, HttpServletResponse response) {

        throw new UnsupportedOperationException();
    }

    @Override
    public void serveImage(
            PortletRequest portletRequest, PortletResponse portletResponse) {

        throw new UnsupportedOperationException();
    }

    @Override
    protected boolean validateChallenge(HttpServletRequest request)
            throws CaptchaException {

        JSONObject jsonObject = null;

        try {
            jsonObject = performRecaptchaSiteVerify(request.getParameter(G_RECAPTCHA_RESPONSE));
        } catch (IOException e) {
            _log.error(e, e);
            throw new CaptchaTextException();
        } catch (JSONException e) {
            _log.error(e, e);
            throw new CaptchaTextException();
        }
        boolean success = false;

        try {
            success = jsonObject.getBoolean("success");
        } catch (JSONException e) {
            _log.error(e, e);
            throw new CaptchaTextException();
        }

        return success;
    }

    @Override
    protected boolean validateChallenge(PortletRequest portletRequest)
            throws CaptchaException {

        HttpServletRequest request = PortalUtil.getHttpServletRequest(
                portletRequest);

        return validateChallenge(request);
    }

    private JSONObject performRecaptchaSiteVerify(String recaptchaResponseToken) throws IOException, JSONException {
        URL url = null;
        StringBuilder postData = new StringBuilder();
        try {
            url = new URL((PropsValues.CAPTCHA_ENGINE_RECAPTCHA_URL_VERIFY));
            addParam(postData, SECRET_PARAM, PrefsPropsUtil.getString(
                    PropsKeys.CAPTCHA_ENGINE_RECAPTCHA_KEY_PRIVATE,
                    PropsValues.CAPTCHA_ENGINE_RECAPTCHA_KEY_PRIVATE));
            addParam(postData, RESPONSE_PARAM, recaptchaResponseToken);
        } catch (MalformedURLException e) {
            _log.error(e, e);
        } catch (UnsupportedEncodingException e) {
            _log.error(e, e);
        } catch (SystemException e) {
            _log.error(e, e);
        }

        return postAndParseJSON(url, postData.toString());
    }

    private JSONObject postAndParseJSON(URL url, String postData) throws IOException, JSONException {
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setDoOutput(true);
        urlConnection.setRequestMethod("POST");
        urlConnection.setRequestProperty(
                "Content-Type", "application/x-www-form-urlencoded");
        urlConnection.setRequestProperty(
                "charset", "UTF-8");
        urlConnection.setRequestProperty(
                "Content-Length", Integer.toString(postData.length()));
        urlConnection.setUseCaches(false);
        urlConnection.getOutputStream()
                .write(postData.getBytes("UTF-8"));
        JSONTokener jsonTokener = new JSONTokener(urlConnection.getInputStream());

        return new JSONObject(jsonTokener);
    }

    private StringBuilder addParam(
            StringBuilder postData, String param, String value)
            throws UnsupportedEncodingException {
        if (postData.length() != 0) {
            postData.append("&");
        }
        return postData.append(
                String.format("%s=%s",
                        URLEncoder.encode(param, "UTF-8"),
                        URLEncoder.encode(value, "UTF-8")));
    }

    private static final String _TAGLIB_PATH =
            "/html/taglib/ui/captcha/recaptcha.jsp";

    private static Log _log = LogFactoryUtil.getLog(ReCaptchaImpl.class);

}