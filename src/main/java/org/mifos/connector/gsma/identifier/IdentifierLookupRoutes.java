package org.mifos.connector.gsma.identifier;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.mifos.connector.gsma.auth.dto.AccessTokenStore;
import org.mifos.connector.gsma.identifier.dto.AccountErrorDTO;
import org.mifos.connector.gsma.identifier.dto.AccountNameResponseDTO;
import org.mifos.connector.gsma.identifier.dto.AccountStatusResponseDTO;
import org.mifos.connector.gsma.zeebe.ZeebeProcessStarter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.mifos.connector.gsma.camel.config.CamelProperties.*;

@Component
public class IdentifierLookupRoutes extends RouteBuilder {

    @Autowired
    private ZeebeProcessStarter zeebeProcessStarter;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccessTokenStore accessTokenStore;

    @Value("${gsma.api.host}")
    private String BaseURL;

    @Value("${gsma.api.account}")
    private String account;

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Override
    public void configure() throws Exception {

        /**
         * Error handling route
         */
        from("direct:account-error")
                .id("account-error")
                .unmarshal().json(JsonLibrary.Jackson, AccountErrorDTO.class)
                .process(exchange -> {
                    exchange.setProperty(ACCOUNT_RESPONSE, exchange.getIn().getBody(AccountErrorDTO.class).getErrorDescription());
                    logger.error(exchange.getIn().getBody(AccountErrorDTO.class).toString());
//                    TODO: Improve Error Handling. Possibly publish errorDescription and fail transaction.
                });

        /**
         * Route when account API call was successful
         */
        from("direct:account-success")
                .id("account-success") // TODO: Change for account status and balance
                .choice()
                    .when(exchange -> exchange.getProperty(ACCOUNT_ACTION, String.class).equals("status"))
                        .log(LoggingLevel.INFO, "Routing to account status handler")
                        .to("direct:account-status-handler")
                    .otherwise()
                        .log(LoggingLevel.INFO, "Routing to account name handler")
                        .to("direct:account-name-handler");

        /**
         * Account status response handler
         */
        from("direct:account-status-handler")
                .id("account-status-handler")
                .unmarshal().json(JsonLibrary.Jackson, AccountStatusResponseDTO.class)
                .process(exchange -> {
                    exchange.setProperty(ACCOUNT_RESPONSE, exchange.getIn().getBody(AccountStatusResponseDTO.class).getAccountStatus());
//                    TODO: Publish available in Zeebe message
                });

        /**
         * Account name response handler
         */
        from("direct:account-name-handler")
                .id("account-name-handler")
                .unmarshal().json(JsonLibrary.Jackson, AccountNameResponseDTO.class)
                .process(exchange -> {
                    exchange.setProperty(ACCOUNT_RESPONSE, exchange.getIn().getBody(AccountNameResponseDTO.class).getName().getFullName());
//                    TODO: Add extra processing as per use case
                });

        /**
         * Route to call GSMA account status API
         */
        from("direct:get-account-details")
                .id("get-account-details")
                .removeHeader("*")
                .setHeader(Exchange.HTTP_METHOD, constant("GET"))
                .setHeader("X-Date", simple(ZonedDateTime.now( ZoneOffset.UTC ).format( DateTimeFormatter.ISO_INSTANT )))
                .setHeader("Authorization", simple("Bearer ${exchangeProperty."+ACCESS_TOKEN+"}"))
                .toD(BaseURL + account + "/${exchangeProperty."+IDENTIFIER_TYPE+"}/${exchangeProperty."+IDENTIFIER+"}/${exchangeProperty."+ACCOUNT_ACTION+"}?bridgeEndpoint=true&throwExceptionOnFailure=false");

        /**
         * Main route for account status
         */
        from("direct:account-route")
                .id("account-route")
                .log(LoggingLevel.INFO, "Getting ${exchangeProperty."+ACCOUNT_ACTION+"} for Identifier")
                .to("direct:get-account-details")
                .log(LoggingLevel.INFO, "Completed ${exchangeProperty."+ACCOUNT_ACTION+"} ${body}")
                .choice()
                    .when(header("CamelHttpResponseCode").isEqualTo("200"))
                        .to("direct:account-success")
                    .otherwise()
                        .to("direct:account-error");

    }
}