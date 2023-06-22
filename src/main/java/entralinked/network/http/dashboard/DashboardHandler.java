package entralinked.network.http.dashboard;

import java.util.Collections;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

import entralinked.Entralinked;
import entralinked.model.dlc.Dlc;
import entralinked.model.dlc.DlcList;
import entralinked.model.pkmn.PkmnGender;
import entralinked.model.pkmn.PkmnInfo;
import entralinked.model.player.DreamEncounter;
import entralinked.model.player.DreamItem;
import entralinked.model.player.Player;
import entralinked.model.player.PlayerManager;
import entralinked.model.player.PlayerStatus;
import entralinked.network.http.HttpHandler;
import entralinked.utility.GsidUtility;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;

/**
 * HTTP handler for requests made to the user dashboard.
 */
public class DashboardHandler implements HttpHandler {
    
    private final DlcList dlcList;
    private final PlayerManager playerManager;
    
    public DashboardHandler(Entralinked entralinked) {
        this.dlcList = entralinked.getDlcList();
        this.playerManager = entralinked.getPlayerManager();
    }
    
    @Override
    public void addHandlers(Javalin javalin) {
        javalin.get("/dashboard/dlc", this::handleRetrieveDlcList);
        javalin.get("/dashboard/profile", this::handleRetrieveProfile);
        javalin.post("/dashboard/profile", this::handleUpdateProfile);
        javalin.post("/dashboard/login", this::handleLogin);
        javalin.post("/dashboard/logout", this::handleLogout);
    }
    
    @Override
    public void configureJavalin(JavalinConfig config) {
        // Configure JSON mapper
        config.jsonMapper(new JavalinJackson(new ObjectMapper()));
        
        // Add dashboard pages
        config.staticFiles.add(staticFileConfig -> {
            staticFileConfig.location = Location.CLASSPATH;
            staticFileConfig.directory = "/dashboard";
            staticFileConfig.hostedPath = "/dashboard";
        });
        
        // Add sprites
        config.staticFiles.add(staticFileConfig -> {
            staticFileConfig.location = Location.CLASSPATH;
            staticFileConfig.directory = "/sprites";
            staticFileConfig.hostedPath = "/sprites";
        });
    }
    
    /**
     * GET request handler for {@code /dashboard/dlc}
     */
    private void handleRetrieveDlcList(Context ctx) {
        // Make sure that the DLC type is present
        String type = ctx.queryParam("type");
        
        if(type == null) {
            ctx.json(Collections.EMPTY_LIST);
            return;
        }
        
        // Send result
        ctx.json(dlcList.getDlcList("IRAO", type).stream().map(Dlc::name).collect(Collectors.toList()));
    }
    
    /**
     * GET request handler for {@code /dashboard/profile}
     */
    private void handleRetrieveProfile(Context ctx) {
        // Validate session
        Player player = ctx.sessionAttribute("player");
        
        if(player == null || player.getStatus() == PlayerStatus.AWAKE) {
            ctx.json(new DashboardStatusMessage("Unauthorized", true));
            ctx.status(HttpStatus.UNAUTHORIZED);
            return;
        }
        
        // Send profile data
        ctx.json(new DashboardProfileMessage(getSpritePath(player.getDreamerInfo()), player));
    }
    
    /**
     * GET request handler for {@code /dashboard/login}
     */
    private void handleLogin(Context ctx) {
        String gsid = ctx.formParam("gsid");
        
        // Check if the Game Sync ID is valid
        if(gsid == null || !GsidUtility.isValidGameSyncId(gsid)) {
            ctx.json(new DashboardStatusMessage("Please enter a valid Game Sync ID.", true));
            return;
        }
        
        Player player = playerManager.getPlayer(gsid);
        
        // Check if the Game Sync ID exists
        if(player == null) {
            ctx.json(new DashboardStatusMessage("This Game Sync ID does not exist.", true));
            return;
        }
        
        // Check if there is stuff to play around with
        if(player.getStatus() == PlayerStatus.AWAKE) {
            ctx.json(new DashboardStatusMessage("Please use Game Sync to tuck in a Pokémon before proceeding.", true));
            return;
        }
        
        // Store session attribute and send response
        ctx.sessionAttribute("player", player);
        ctx.json(new DashboardStatusMessage("ok")); // heh
    }
    
    /**
     * POST request handler for {@code /dashboard/logout}
     */
    private void handleLogout(Context ctx) {
        // Who cares if the session actually exists? I sure don't.
        ctx.consumeSessionAttribute("player");
        ctx.json(Collections.EMPTY_MAP);
    }
    
    /**
     * POST request handler for {@code /dashboard/profile}
     */
    private void handleUpdateProfile(Context ctx) {
        // Check if session exists
        Player player = ctx.sessionAttribute("player");
        
        if(player == null || player.getStatus() == PlayerStatus.AWAKE) {
            ctx.json(new DashboardStatusMessage("Unauthorized", true));
            ctx.status(HttpStatus.UNAUTHORIZED);
            return;
        }
        
        // Validate request data
        DashboardProfileUpdateRequest request = ctx.bodyAsClass(DashboardProfileUpdateRequest.class);
        String error = validateProfileUpdateRequest(player, request);
        
        if(error != null) {
            ctx.json(new DashboardStatusMessage("Profile data was NOT saved: " + error, true));
            return;
        }
        
        // Update profile
        player.setStatus(PlayerStatus.WAKE_READY);
        player.setEncounters(request.encounters());
        player.setItems(request.items());
        player.setCGearSkin(request.cgearSkin().equals("none") ? null : request.cgearSkin());
        player.setDexSkin(request.dexSkin().equals("none") ? null : request.dexSkin());
        player.setMusical(request.musical().equals("none") ? null : request.musical());
        player.setLevelsGained(request.gainedLevels());
        
        // Try to save profile data
        if(!playerManager.savePlayer(player)) {
            ctx.json(new DashboardStatusMessage("Profile data could not be saved because of an error.", true));
            return;
        }
        
        // Send response if all succeeded
        ctx.json(new DashboardStatusMessage("Your changes have been saved. Use Game Sync to wake up your Pokémon and download your selected content."));
    }
    
    /**
     * Validates a {@link DashboardProfileUpdateRequest} and returns an error string if the data is invalid.
     * If the data is valid, {@code null} is returned instead.
     */
    private String validateProfileUpdateRequest(Player player, DashboardProfileUpdateRequest request) {
        // Validate encounters
        if(request.encounters().size() > 10) {
            return "Encounter list size exceeds the limit.";
        }
        
        for(DreamEncounter encounter : request.encounters()) {
            if(encounter.species() < 1 || encounter.species() > 493) {
                return "Species is out of range.";
            } else if(encounter.move() < 1 || encounter.move() > 559) {
                return "Move ID is out of range.";
            } else if(encounter.animation() == null) {
                return "Animation is undefined.";
            }
            
            // TODO validate form maybe idk
        }
        
        // Validate items       
        if(request.items().size() > 20) {
            return "Item list size exceeds the limit.";
        }
        
        for(DreamItem item : request.items()) {
            if(item.id() < 0 || item.id() > 638) {
                return "Item ID is out of range";
            } else if(item.id() > 626 && !player.getGameVersion().isVersion2()) {
                return "You have selected one or more items that are exclusive to Black Version 2 and White Version 2.";
            }
            
            if(item.quantity() < 0 || item.quantity() > 20) {
                return "Item quantity is out of range.";
            }
        }
        
        // Validate gained levels
        if(request.gainedLevels() < 0 || request.gainedLevels() > 99) {
            return "Gained levels is out of range.";
        }
        
        return null;
    }
    
    private String getSpritePath(PkmnInfo info) {
        String basePath = "/sprites/pokemon/%s".formatted(info.isShiny() ? "shiny" : "normal");
        String path = null;
        
        if(info.form() > 0) {
            path = "%s/%s-%s.png".formatted(basePath, info.species(), info.form());
        } else if(info.gender() == PkmnGender.FEMALE) {
            path = "%s/female/%s.png".formatted(basePath, info.species());
        }
        
        if(path == null || getClass().getResource(path) == null) {
            return "%s/%s.png".formatted(basePath, info.species());
        }
        
        return path;
    }
}