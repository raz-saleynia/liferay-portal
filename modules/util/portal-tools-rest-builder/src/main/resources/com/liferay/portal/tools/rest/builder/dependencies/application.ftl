package ${configYAML.apiPackagePath}.internal.jaxrs.application;

import javax.annotation.Generated;

import javax.ws.rs.core.Application;

import org.osgi.service.component.annotations.Component;

/**
 * @author ${configYAML.author}
 * @generated
 */
@Component(
	property = {
		"oauth2.scope.checker.type=annotations",
		"osgi.jaxrs.application.base=${configYAML.application.baseURI}",
		"osgi.jaxrs.extension.select=(osgi.jaxrs.name=Liferay.Vulcan.AcceptLanguageContextProvider)",
		"osgi.jaxrs.extension.select=(osgi.jaxrs.name=Liferay.Vulcan.CompanyContextProvider)",
		"osgi.jaxrs.extension.select=(osgi.jaxrs.name=Liferay.Vulcan.JSONMessageBodyReader)",
		"osgi.jaxrs.extension.select=(osgi.jaxrs.name=Liferay.Vulcan.JSONMessageBodyWriter)",
		"osgi.jaxrs.extension.select=(osgi.jaxrs.name=Liferay.Vulcan.NoSuchModelExceptionMapper)",
		"osgi.jaxrs.extension.select=(osgi.jaxrs.name=Liferay.Vulcan.PaginationContextProvider)",
		"osgi.jaxrs.extension.select=(osgi.jaxrs.name=Liferay.Vulcan.PortalExceptionMapper)",
		"osgi.jaxrs.extension.select=(osgi.jaxrs.name=Liferay.Vulcan.PrincipalExceptionMapper)",
		"osgi.jaxrs.name=${configYAML.application.name}"
	},
	service = Application.class
)
@Generated("")
public class ${configYAML.application.className} extends Application {
}