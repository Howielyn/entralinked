package entralinked.network.http.nas;

import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import entralinked.Configuration;
import entralinked.Entralinked;
import entralinked.model.user.ServiceCredentials;
import entralinked.model.user.User;
import entralinked.model.user.UserManager;
import entralinked.network.http.HttpHandler;
import entralinked.network.http.HttpRequestHandler;
import entralinked.serialization.UrlEncodedFormFactory;
import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * HTTP handler for requests made to {@code nas.nintendowifi.net}
 */
public class NasHandler implements HttpHandler {
    
    private static final Logger logger = LogManager.getLogger();
    private final ObjectMapper mapper = new ObjectMapper(new UrlEncodedFormFactory()).registerModule(new JavaTimeModule());
    private final Configuration configuration;
    private final UserManager userManager;
    
    public NasHandler(Entralinked entralinked) {
        this.configuration = entralinked.getConfiguration();
        this.userManager = entralinked.getUserManager();
    }
    
    @Override
    public void addHandlers(Javalin javalin) {
        javalin.post("/ac", this::handleNasRequest);
    }
    
    /**
     * POST base handler for {@code /ac}
     * Deserializes requests and processes them accordingly.
     */
    private void handleNasRequest(Context ctx) throws IOException {
        // Deserialize body into a request object
        NasRequest request = mapper.readValue(ctx.body(), NasRequest.class);
        logger.debug("Received {}", request);
        
        // Determine handler function based on request action
        HttpRequestHandler<NasRequest> handler = switch(request.action()) {
            case "login" -> this::handleLogin;
            case "acctcreate" -> this::handleCreateAccount;
            case "SVCLOC" -> this::handleRetrieveServiceLocation;
            default -> throw new IllegalArgumentException("Invalid POST request action: " + request.action());
        };
        
        // Process the request
        handler.process(request, ctx);
    }
    
    /**
     * POST handler for {@code /ac action=login}
     */
    private void handleLogin(NasRequest request, Context ctx) throws IOException {
        // Make sure branch code is present
        if(request.branchCode() == null) {
            logger.debug("Rejecting NAS login request because no branch code is present");
            result(ctx, NasReturnCode.BAD_REQUEST);
            return;
        }
        
        String userId = request.userId();
        User user = userManager.authenticateUser(userId, request.password());
        
        // Check if user exists
        if(user == null) {
            if(!configuration.allowWfcRegistrationThroughLogin()) {
                result(ctx, NasReturnCode.USER_NOT_FOUND);
                return;
            }
            
            // Try to register, if the configuration allows it
            if(!UserManager.isValidUserId(userId) || userManager.doesUserExist(userId) 
                    || userManager.registerUser(userId, request.password()) == null) {
                // Oh well, try again!
                result(ctx, NasReturnCode.USER_NOT_FOUND);
                return;
            }
            
            // Should *never* return null in this location
            user = userManager.authenticateUser(userId, request.password());
            logger.info("Created account for user {}", user.getFormattedId(!configuration.logSensitiveInfo()));
        }
        
        // Prepare GameSpy server credentials
        ServiceCredentials credentials = userManager.createServiceSession(user, "gamespy", request.branchCode());
        logger.info("Created GameSpy session for user {}", user.getFormattedId(!configuration.logSensitiveInfo()));
        result(ctx, new NasLoginResponse("gamespy.com", credentials.authToken(), credentials.challenge()));
    }
    
    /**
     * POST handler for {@code /ac action=acctcreate}
     */
    private void handleCreateAccount(NasRequest request, Context ctx) throws IOException {
        String userId = request.userId();
        
        // Check if user ID is invalid or duplicate
        if(!UserManager.isValidUserId(userId) || userManager.doesUserExist(userId)) {
            result(ctx, NasReturnCode.USER_ALREADY_EXISTS);
            return;
        }
        
        // Try to register user
        User user = userManager.registerUser(userId, request.password());
        
        if(user == null) {
            result(ctx, NasReturnCode.INTERNAL_SERVER_ERROR);
            return;
        }
        
        logger.info("Created account for user {}", user.getFormattedId(!configuration.logSensitiveInfo()));
        result(ctx, NasReturnCode.REGISTRATION_SUCCESS);
    }
    
    /**
     * POST handler for {@code /ac action=SVCLOC}
     */
    private void handleRetrieveServiceLocation(NasRequest request, Context ctx) throws IOException {
        // Authenticate user
        User user = userManager.authenticateUser(request.userId(), request.password());
        
        if(user == null) {
            result(ctx, NasReturnCode.USER_NOT_FOUND);
            return;
        }
        
        String type = request.serviceType();
        
        // Determine service location from type
        String service = switch(type) {
            case "0000" -> "external"; // External game-specific service
            case "9000" -> "dls1.nintendowifi.net"; // Download server
            default -> throw new IllegalArgumentException("Invalid service type: " + type);
        };
                
        // Prepare user credentials
        ServiceCredentials credentials = userManager.createServiceSession(user, service, null);
        logger.info("Created {} session for user {}", 
                type.equals("0000") ? "PGL" : type.equals("9000") ? "DLS1" : "this should never be logged", user.getFormattedId(!configuration.logSensitiveInfo()));
        result(ctx, new NasServiceLocationResponse(true, service, credentials.authToken()));
    }
    
    /**
     * Sets context result to the specified response serialized as a URL encoded form.
     */
    private void result(Context ctx, NasResponse response) throws IOException {
        ctx.result(mapper.writeValueAsString(response));
    }
    
    /**
     * Calls {@link #result(Context, NasResponse)} where {@code NasResponse} is a {@link NasStatusResponse}
     * with the specified return code as its parameter.
     */
    private void result(Context ctx, NasReturnCode returnCode) throws IOException {
        result(ctx, new NasStatusResponse(returnCode));
    }
}
