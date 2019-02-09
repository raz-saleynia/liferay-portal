package com.liferay.portlet.journalcontent.util;

import com.liferay.portal.kernel.xml.Element;
import com.liferay.portal.theme.ThemeDisplay;
import com.liferay.portlet.journal.model.JournalArticleDisplay;

public interface ExtJournalContent extends JournalContent{

	public JournalArticleDisplay getDisplay(
            long groupId, String articleId, double version, String templateId,
            String viewMode, String languageId, ThemeDisplay themeDisplay, int page,
            String xmlRequest, Element requestElement);
}
