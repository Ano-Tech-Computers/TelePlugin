package no.atc.floyd.bukkit.tele;


import org.bukkit.entity.Player;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.block.Block;
import org.bukkit.command.*;

import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.DefaultFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.ApplicableRegionSet;


/**
* TelePlugin plugin for Bukkit
*
* @author FloydATC
*/
public class TelePlugin extends JavaPlugin implements Listener {
    //public static Permissions Permissions = null;
	public static final String MSG_PREFIX = ChatColor.GRAY + "[" + ChatColor.GOLD + "TP" + ChatColor.GRAY + "] ";
	public static final ChatColor COLOR_INFO = ChatColor.AQUA;
	public static final ChatColor COLOR_WARNING = ChatColor.RED;
	public static final ChatColor COLOR_NAME = ChatColor.GOLD;
	
	public final File req_dir = new File(this.getDataFolder(), "requests");
	public final File loc_dir = new File(this.getDataFolder(), "locations");
	public final File warp_dir = new File(this.getDataFolder(), "warps");
	private final long cooldown = 86400 * 1000; // Milliseconds
	private ConcurrentHashMap<String,ConcurrentHashMap<Integer,Location>> locs = new ConcurrentHashMap<String,ConcurrentHashMap<Integer,Location>>();
    private Integer max_tpback = 1440; // Number of MINUTES to keep
    WorldGuardPlugin worldguard = null;


    public void onDisable() {
    	for (String pname : locs.keySet()) {
    		saveLocations(pname);
    	}
    }

    public void onEnable() {
    	// Set up directory for request, denial and permission tokens.
    	// Clear out any stale files (i.e. older than cooldown)
    	if (req_dir.exists() == false) { req_dir.mkdirs(); }
    	File[] files = req_dir.listFiles();
    	long now = System.currentTimeMillis();
    	for (int i = 0; i < files.length; i++) {
    		if (files[i].lastModified() + cooldown < now) {
    			files[i].delete();
    		}
    	}

    	// Set up directory for player location data (used for /tpback)
    	if (loc_dir.exists() == false) { loc_dir.mkdirs(); }

    	// WorldGuard integration
    	Plugin wg = getServer().getPluginManager().getPlugin("WorldGuard");
    	if (wg == null || !(wg instanceof WorldGuardPlugin)) {
    		getLogger().info("WorldGuard not loaded, will not detect PVP regions");
    	} else {
    		worldguard = (WorldGuardPlugin) wg;
    		getLogger().info("Using WorldGuard to detect PVP regions");
    	}

    	// Register event handlers
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(this, this);
    }

    public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args ) {
    	String cmdname = cmd.getName().toLowerCase();
        Player player = null;
        if (sender instanceof Player) {
        	player = (Player) sender;
            //getLogger().info("player="+player+" cmd="+cmdname+" args="+Arrays.toString(args));
        }

        // See if any options were specified
        Boolean force = false;
        Integer options = numOptions(args);
        if (options > 0) {
            String[] revised_args = new String[args.length - options];
            Integer index = 0;
            for (String s: args) {
            	if (s.startsWith("--")) {
            		// Supported options go here
            		if (s.equalsIgnoreCase("--force")) {
            			force = true;
            		}
            	} else {
            		revised_args[index] = s;
            		index++;
            	}
            }
        	getLogger().info("done revising argument list");
            args = revised_args;
        }

    	if (cmdname.equalsIgnoreCase("tp") && player != null && player.hasPermission("teleplugin.tp")) {
    		if (args.length == 0 || args.length > 3) {
    			player.sendMessage(MSG_PREFIX + COLOR_INFO + "Valid syntax:");
    			player.sendMessage(MSG_PREFIX + COLOR_INFO + "/tp <player>");
    			player.sendMessage(MSG_PREFIX + COLOR_INFO + "/tp <x> <z>");
    			player.sendMessage(MSG_PREFIX + COLOR_INFO + "/tp <x> <z> <world>");
    			return true;
    		}
    		if (args.length == 1) {
    			// Teleport to player
    			if (teleport(player.getName(), args[0], force)) {
    				getLogger().info(player.getName() + " teleported to " + args[0] );
    			} else {
    				player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Could not teleport you to " + COLOR_NAME + args[0]);
    				getLogger().info(player.getName() + " could not teleport to " + args[0] );
    			}
    			return true;
    		}
    		if (args.length == 2) {
    			// Teleport to specific coordinates in current world at maximum height
    			Location loc = player.getLocation();
    			Integer x = Integer.parseInt(args[0]);
    			Integer z = Integer.parseInt(args[1]);
    			Integer y = loc.getWorld().getHighestBlockYAt(x, z);
    			loc.setX(x);
    			loc.setZ(z);
    			loc.setY(y);
    			if (safeTeleport(player, loc, force)) {
    				player.sendMessage(MSG_PREFIX + COLOR_INFO + "Teleported you to coordinates x=" +x+ " z="+z);
    				getLogger().info(player.getName() + " teleported to coordinates x=" +x+ " z="+z);
    			} else {
    				player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Could not teleport you to coordinates x="+x+" z="+z);
    				getLogger().info(player.getName() + " could not teleport to coordinates x="+x+" z="+z);
    			}
    			return true;
    		}
    		if (args.length == 3) {
    			// Teleport to specific coordinates in alternate world at maximum height
    			Location loc = player.getLocation();
    			Integer x = Integer.parseInt(args[0]);
    			Integer z = Integer.parseInt(args[1]);
    			World w = player.getServer().getWorld(args[2]);
    			if (w == null) {
    				player.sendMessage(MSG_PREFIX + COLOR_WARNING + "There is no world called " + args[2]);
    				return true;
    			}
    			Integer y = w.getHighestBlockYAt(x, z);
    			loc.setX(x);
    			loc.setZ(z);
    			loc.setY(y);
    			loc.setWorld(w);
    			if (safeTeleport(player, loc, force)) {
    				player.sendMessage(MSG_PREFIX + COLOR_INFO + "Teleported you to coordinates x=" +x+ " z="+z+ " in world "+w.getName());
    				getLogger().info(player.getName() + " teleported to oordinates x=" +x+ " z="+z+ " in world "+w.getName());
    			} else {
    				player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Could not teleport you to oordinates x=" +x+ " z="+z+ " in world "+w.getName());
    				getLogger().info(player.getName() + " could not teleport to oordinates x=" +x+ " z="+z+ " in world "+w.getName());
    			}
    			return true;
    		}
    	}
    	if (cmdname.equalsIgnoreCase("tphelp") && player != null) {
			player.sendMessage(MSG_PREFIX + COLOR_INFO + "Personal teleportation commands: " + COLOR_WARNING + "[EXPERIMENTAL]");
			player.sendMessage(MSG_PREFIX + COLOR_NAME + "/tpa <name>  " + COLOR_INFO + "Request teleport to player");
			player.sendMessage(MSG_PREFIX + COLOR_NAME + "/tpy <name>  " + COLOR_INFO + "Grant teleport access");
			player.sendMessage(MSG_PREFIX + COLOR_NAME + "/tpn <name>  " + COLOR_INFO + "Deny teleport access");
			player.sendMessage(MSG_PREFIX + COLOR_INFO + "Access is granted for 24 hours or until denied");
			player.sendMessage(MSG_PREFIX + COLOR_INFO + "Repeated requests/grants/denials are ignored");
			if (player != null && player.hasPermission("teleplugin.tpa") == false) {
				player.sendMessage(MSG_PREFIX + COLOR_WARNING + "You do not yet have permission to use this feature");
			}
   			return true;
    	}
    	if (cmdname.equalsIgnoreCase("tpa") && player != null && player.hasPermission("teleplugin.tpa")) {
    		force = false; // Disallowed
    		if (args.length == 0 || args.length > 1) {
    			player.sendMessage(MSG_PREFIX + COLOR_INFO + "Valid syntax:");
    			player.sendMessage(MSG_PREFIX + COLOR_INFO + "/tpa <player>");
    			return true;
    		}
    		if (args.length == 1) {
    	        Player target = null;
    	        if (args.length == 1) {
    	        	target = this.getServer().getPlayer(args[0]);
    	        	if (target != null) {
    	        		args[0] = target.getName();
    	        	}
    	        }
    			if (target != null && target.hasPermission("teleplugin.tpa") == false) {
    				player.sendMessage(MSG_PREFIX + COLOR_NAME + args[0] + COLOR_WARNING + " does not yet have permission to use this feature");
    				return true;
    			}
    			if (has_denial(player.getName(), args[0])) {
    				player.sendMessage(MSG_PREFIX + COLOR_NAME + args[0] + COLOR_WARNING + " has denied you teleport permission.");
    				return true;
    			}
    			if (has_permission(player.getName(), args[0])) {
	    			if (teleport(player.getName(), args[0], force)) {
	    				getLogger().info(player.getName() + " teleported to " + args[0] );
	    				cancel_request(player.getName(), args[0]);
	    			} else {
	    				player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Could not teleport you to " + COLOR_NAME + args[0]);
	    				getLogger().info(player.getName() + " could not teleport to " + args[0] );
	    			}
    			} else {
    				if (request_permission(player.getName(), args[0])) {
    					player.sendMessage(MSG_PREFIX + COLOR_INFO + "Requested teleport to " + COLOR_NAME + args[0]);
    				} else {
    					player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Could not request teleport to " + COLOR_NAME + args[0] + COLOR_WARNING + " at this time");
    				}
    			}
    			return true;
    		}
    	}
    	if (cmdname.equalsIgnoreCase("tpy") && player != null && player.hasPermission("teleplugin.tpa")) {
    		force = false; // Disallowed
            Player target = null;
    		if (args.length == 0 || args.length > 1) {
    			player.sendMessage(MSG_PREFIX + COLOR_INFO + "Valid syntax:");
    			player.sendMessage(MSG_PREFIX + COLOR_INFO + "/tpy <player>");
    			return true;
    		}
            if (args.length == 1) {
            	target = this.getServer().getPlayer(args[0]);
            	if (target != null) {
            		args[0] = target.getName();
            	}
            }
    		if (args.length == 1) {
    			if (target != null && target.hasPermission("teleplugin.tpa") == false) {
    				player.sendMessage(MSG_PREFIX + COLOR_NAME + args[0] + COLOR_WARNING + " does not yet have permission to use this feature");
    				return true;
    			}
    			if (grant_permission(player.getName(), args[0])) {
    				player.sendMessage(MSG_PREFIX + COLOR_INFO + "Teleport permission granted to " + COLOR_NAME + args[0]);
    			} else {
					player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Could not grant teleport permission to " + COLOR_NAME + args[0] + COLOR_WARNING + " at this time");
    			}

    			if (has_request(args[0], player.getName()) && has_permission(args[0], player.getName())) {
	    			if (teleport(args[0], player.getName(), force)) {
	    				getLogger().info(args[0] + " teleported to " + player.getName() );
	        			cancel_request(args[0], player.getName());
	    			}
    			}
    			return true;
    		}
    	}
    	if (cmdname.equalsIgnoreCase("tpn") && player != null && player.hasPermission("teleplugin.tpa")) {
    		if (args.length == 0 || args.length > 1) {
    			player.sendMessage(MSG_PREFIX + COLOR_INFO + "Valid syntax:");
    			player.sendMessage(MSG_PREFIX + COLOR_INFO + "/tpn <player>");
    			return true;
    		}
    		if (args.length == 1) {
    			if (deny_permission(player.getName(), args[0])) {
    				player.sendMessage(MSG_PREFIX + COLOR_INFO + "Teleport permission denied to " + COLOR_NAME + args[0]);
    			} else {
					player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Could not deny teleport permission to " + COLOR_NAME + args[0] + COLOR_WARNING + " at this time");
    			}
    			return true;
    		}
    	}
    	if (cmdname.equalsIgnoreCase("tphere") && player != null && player.hasPermission("teleplugin.tphere")) {
    		if (args.length == 0) {
    			player.sendMessage(MSG_PREFIX + COLOR_INFO + "Valid syntax:");
    			player.sendMessage(MSG_PREFIX + COLOR_INFO + "/tphere <player> [<player> ...]");
    			return true;
    		}
    		if (args.length >= 1) {
    			for (String subject : args) {
    				if (teleport(subject, player.getName(), force)) {
    					getLogger().info(player.getName() + " teleported " + args[0] + " to self");
        				player.sendMessage(MSG_PREFIX + COLOR_INFO + "Teleported " + COLOR_NAME + args[0] + COLOR_INFO + " to you");
    				} else {
        				player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Could not teleport " + COLOR_NAME + args[0] + COLOR_WARNING + " to you");
    				}
    			}
    			return true;
    		}
    	}
    	if (cmdname.equalsIgnoreCase("tpto") && (player == null || player.hasPermission("teleplugin.tpto"))) {
    		if (args.length < 2) {
    			player.sendMessage(MSG_PREFIX + COLOR_INFO + "Valid syntax:");
    			player.sendMessage(MSG_PREFIX + COLOR_INFO + "/tpto <to_player> <player> [<player> ...]");
    			return true;
    		}
    		if (args.length >= 2) {
        		String performer = "(Server)";
        		if (player != null) {
        			performer = player.getName();
        		}
    			String destination = args[args.length-1];
    			for (Integer i=0; i<args.length-1; i++) {
    				if (teleport(args[i], destination, force)) {
    					getLogger().info(performer + " teleported " + args[i] + " to " + destination);
        				if (player != null) {
        					player.sendMessage(MSG_PREFIX + COLOR_INFO + "Teleported " + COLOR_NAME + args[i] + COLOR_INFO + " to " + COLOR_NAME + destination);
        				}
    				} else {
    					getLogger().info("Could not teleport " + args[i] + " to " + destination);
        				if (player != null) {
        					player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Could not teleport " + COLOR_NAME + args[i] + COLOR_WARNING + " to " + COLOR_NAME + destination);
        				}
    				}
    			}
    			return true;
    		}
    	}
/*    	if (cmdname.equalsIgnoreCase("spawn")) {
    		if (args.length > 0) {
    			// Spawn others
    			if (player == null || player.hasPermission("teleplugin.spawn.other")) {
    				String admin = "Server";
    				if (player != null) {
    					admin = player.getName();
    				}
    				for (String pname : args) {
    					Player p = getServer().getPlayer(pname);
    					if (p != null) {
    						p.teleport(p.getWorld().getSpawnLocation());
    						p.sendMessage(MSG_PREFIX + COLOR_INFO + "You were respawned by " + admin);
        					getLogger().info("Player " + pname + " was respawned by " + admin);
    					}
    				}
    			} else {
					player.sendMessage(MSG_PREFIX + COLOR_WARNING + "You do not have permission to spawn other players");
    			}
    		} else {
    			// Spawn self
    			if (player != null && player.hasPermission("teleplugin.spawn.self")) {
    				player.teleport(player.getWorld().getSpawnLocation());
					player.sendMessage(MSG_PREFIX + COLOR_INFO + "You were respawned");
					getLogger().info("Player " + player.getName() + " respawned");
    			} else {
					player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Spawn who?");
    			}
    		}
    	}
*/    	if (cmdname.equalsIgnoreCase("tpback") && player != null && player.hasPermission("teleplugin.tpback")) {
    		if (args.length == 0 || args.length > 2) {
    			player.sendMessage(MSG_PREFIX + COLOR_INFO + "Valid syntax:");
    			player.sendMessage(MSG_PREFIX + COLOR_INFO + "/tpback <minutes> [<player>]");
    			player.sendMessage(MSG_PREFIX + COLOR_INFO + "/tpback <hh>:<mm> [<player>]");
    			return true;
    		}
    		if (args.length == 1) {
    			// Get delta
    			Integer delta = 1;
				// Is this a time in HH:MM format? Convert to delta
				if (args[0].matches("^[0-2][0-9]:[0-5][0-9]$")) {
					args[0] = time_to_delta(args[0]);
				}

				// The argument should now be an integer
    			try {
    				delta = Integer.valueOf(args[0]);
    			}
    			catch (Exception e) {
    				player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Expected a number");
    				//e.printStackTrace();
    				return false;
    			}
    			// Teleport back to own location
    			Location loc = getLocation(player.getName(), delta);
    			if (loc == null) {
    				player.sendMessage(MSG_PREFIX + COLOR_WARNING + "No location recorded "+delta+" minute"+(delta==1?"":"s")+" ago");
    				Integer oldest = getOldestDelta(player.getName());
    				if (oldest != null) {
    					player.sendMessage(MSG_PREFIX + COLOR_WARNING + "The earliest location is "+oldest+" minute"+(oldest==1?"":"s")+" old");
    				}
    			} else {
    				if (safeTeleport(player, loc, force)) {
    					getLogger().info(player.getName() + " teleported "+delta+" minute"+(delta==1?"":"s")+" back");
    					player.sendMessage(MSG_PREFIX + COLOR_INFO + "Teleported you "+delta+" minute"+(delta==1?"":"s")+" back");
    				} else {
        				player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Teleport to "+delta+" minute"+(delta==1?"":"s")+" ago failed");
    				}
    			}
				return true;
    		}
    		if (args.length == 2 && player.hasPermission("teleplugin.tpback.other")) {
				// Is this a time in HH:MM format? Convert to delta
				if (args[0].matches("^[0-2][0-9]:[0-5][0-9]$")) {
					args[0] = time_to_delta(args[0]);
				}
    			// Get delta
    			Integer delta = 1;
    			try {
    				delta = Integer.valueOf(args[0]);
    			}
    			catch (Exception e) {
    				player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Expected a number");
    				//e.printStackTrace();
    				return false;
    			}
    			// Get player name
    			Player p = null;
    			String pname = args[1];
    			p = player.getServer().getPlayer(pname);
    			if (p != null) {
    				pname = p.getName();
    			}
    			// Teleport back to player's location
    			Location loc = getLocation(pname, delta);
    			if (loc == null) {
    				player.sendMessage(MSG_PREFIX + COLOR_WARNING + "No location recorded for " + COLOR_NAME+pname+COLOR_WARNING + " "+delta+" minute"+(delta==1?"":"s")+" ago");
    				Integer oldest = getOldestDelta(pname);
    				if (oldest != null) {
    					player.sendMessage(MSG_PREFIX + COLOR_WARNING + "The earliest location for " + COLOR_NAME+pname+COLOR_WARNING + " is "+oldest+" minute"+(oldest==1?"":"s")+" old");
    				}
    			} else {
    				if (safeTeleport(player, loc, force)) {
    					getLogger().info(player.getName() + " teleported to where "+pname+" was "+delta+" minute"+(delta==1?"":"s")+" ago");
    					player.sendMessage(MSG_PREFIX + COLOR_INFO + "Teleported you to where " + COLOR_NAME+pname+COLOR_WARNING + " was "+delta+" minute"+(delta==1?"":"s")+" ago");
    				} else {
        				player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Teleport to where " + COLOR_NAME+pname+COLOR_WARNING + " was "+delta+" minute"+(delta==1?"":"s")+" ago failed");
    				}
    			}
				return true;

    		}
    	}

    	if (cmdname.equalsIgnoreCase("warp")) {
    		if (args.length == 0) {
    			respond(player, MSG_PREFIX + COLOR_INFO + "Syntax:");
       			respond(player, MSG_PREFIX + COLOR_INFO + "/warp <place> [--force|<player> [...]]");
    			return true;
    		}
    		Warp w = new Warp(args[0], player, this);
    		if (w.exists()) {
    			if (w.usePermitted(player)) {
    				if (args.length == 1) {
    					// Warp self
    					if (player == null) {
    						respond(player, MSG_PREFIX + COLOR_WARNING + "Must specify a player from the console");
    					} else {
		    				if (safeTeleport(player, w.location(), force)) {
		    					getLogger().info(player.getName() + " warped to "+args[0]);
		    					respond(player, MSG_PREFIX + COLOR_INFO + "Warped you to "+args[0]);
		    					w.touch();
		    				} else {
		    					respond(player, MSG_PREFIX + COLOR_WARNING + "Warp to "+args[0]+" failed");
		    				}
    					}
    				} else {
    					// Warp others
    					if (player.hasPermission("teleplugin.warpother")) {
    						for (Integer i = 1; i < args.length; i++) {
    							Player p = getServer().getPlayer(args[i]);
    							if (p != null) {
    								if (safeTeleport(p, w.location(), false)) {
    									getLogger().info(player.getName() + " warped "+p.getName()+" to "+args[0]);
    			    					respond(player, MSG_PREFIX + COLOR_INFO + "Warped "+p.getName()+" to "+args[0]);
    			    					respond(p, MSG_PREFIX + COLOR_INFO + ""+player.getName()+" warped you to "+w.name());
    			    					w.touch();
    								} else {
    			    					respond(player, MSG_PREFIX + COLOR_WARNING + ""+p.getName()+" was not warped to "+args[1]);
    								}
    							} else {
    		    					respond(player, MSG_PREFIX + COLOR_WARNING + "Player "+args[i]+" is not online");
    							}
    						}
    					} else {
    						getLogger().info(player.getName() + " warp others to "+args[0]+" denied");
	    					respond(player, MSG_PREFIX + COLOR_WARNING + "You don't have permission to warp other players");
    					}
    				}
    			} else {
    				getLogger().info(player.getName() + " warp to "+args[0]+" denied");
        			respond(player, MSG_PREFIX + COLOR_WARNING + "You don't have permission to use warp point '"+args[0]+"'");
    			}
    		} else {
    			respond(player, MSG_PREFIX + COLOR_WARNING + "Warp point '"+args[0]+"' not found");
    		}

    		return true;
    	}


    	if (cmdname.equalsIgnoreCase("setwarp")) {
    		if (args.length == 0 || args.length > 1) {
    			respond(player, MSG_PREFIX + COLOR_INFO + "Syntax:");
       			respond(player, MSG_PREFIX + COLOR_INFO + "/setwarp <place>");
    			return true;
    		}
    		if (player == null) {
       			respond(player, MSG_PREFIX + COLOR_WARNING + "You can't set a warp point from the console");
    			return true;
    		}
    		Warp w = new Warp(args[0], player, this);
    		if (w.exists()) {
    			respond(player, MSG_PREFIX + COLOR_WARNING + "Warp point '"+args[0]+"' already exists");
    		} else {
    			if (w.createPermitted(player)) {
    				if (w.create()) {
    					getLogger().info(player.getName()+" created warp point "+args[0]);
    					respond(player, MSG_PREFIX + COLOR_INFO + "Warp point '"+args[0]+"' was created");
    				} else {
    					getLogger().warning("Error creating warp point '"+args[0]+"': "+w.error());
    					respond(player, MSG_PREFIX + COLOR_WARNING + "Internal error: "+w.error());
    				}
    			} else {
					getLogger().warning(player.getName()+" was not permitted to create warp point "+args[0]);
        			respond(player, MSG_PREFIX + COLOR_WARNING + "You don't have permission to create warp point '"+args[0]+"'");
    			}
    		}
    		return true;
    	}


    	if (cmdname.equalsIgnoreCase("delwarp")) {
    		if (args.length == 0 || args.length > 1) {
    			respond(player, MSG_PREFIX + COLOR_INFO + "Syntax:");
       			respond(player, MSG_PREFIX + COLOR_INFO + "/delwarp <place>");
    			return true;
    		}
    		Warp w = new Warp(args[0], player, this);
    		if (w.exists()) {
    			if (w.deletePermitted(player)) {
    				w.delete();
					getLogger().info(player.getName()+" deleted warp point "+args[0]);
        			respond(player, MSG_PREFIX + COLOR_INFO + "Warp point '"+args[0]+"' has now been deleted");
    			} else {
    				getLogger().warning(player.getName()+" was not permitted to delete warp point "+args[0]);
        			respond(player, MSG_PREFIX + COLOR_WARNING + "You don't have permission to delete warp point '"+args[0]+"'");
    			}
    		} else {
    			respond(player, MSG_PREFIX + COLOR_WARNING + "Warp point '"+args[0]+"' does not exist");
    		}
    		return true;
    	}


    	if (cmdname.equalsIgnoreCase("movewarp")) {
    		if (args.length == 0 || args.length > 1) {
    			respond(player, MSG_PREFIX + COLOR_INFO + "Syntax:");
       			respond(player, MSG_PREFIX + COLOR_INFO + "/movewarp <place>");
    			return true;
    		}
    		if (player == null) {
       			respond(player, MSG_PREFIX + COLOR_WARNING + "You can't move a warp point from the console");
    			return true;
    		}
    		Warp w = new Warp(args[0], player, this);
    		if (w.exists()) {
    			if (w.movePermitted(player)) {
    				if (w.delete() && w.create()) {
    					getLogger().info(player.getName()+" moved warp point "+args[0]);
    					respond(player, MSG_PREFIX + COLOR_INFO + "Warp point '"+args[0]+"' was moved");
    				} else {
    					getLogger().warning("Error moving warp point"+args[0]+": "+w.error());
    					respond(player, MSG_PREFIX + COLOR_WARNING + "Internal error: "+w.error());
    				}
    			} else {
    				getLogger().warning(player.getName()+" was not permitted to move warp point "+args[0]);
        			respond(player, MSG_PREFIX + COLOR_WARNING + "You don't have permission to move warp point '"+args[0]+"'");
    			}
    		} else {
    			respond(player, MSG_PREFIX + COLOR_WARNING + "Warp point '"+args[0]+"' does not exist");
    		}
    		return true;
    	}


      if (cmdname.equalsIgnoreCase("listwarps"))
      {
        if (args.length > 2)
        {
          respond(player, MSG_PREFIX + COLOR_INFO + "Syntax:");
          respond(player, MSG_PREFIX + COLOR_INFO + "/listwarps [<player>] [<page>]");
          return true;
        }

        // Parse arguments
        String owner = null;
        int page = 0;
        if (args.length >= 1)
        {
          if (Character.isDigit(args[0].charAt(0)))
          {
            try {
              page = Integer.parseInt(args[0]) - 1;
            }
            catch (NumberFormatException e) {
              player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Expected a number");
              //e.printStackTrace();
              return false;
            }
            page = Integer.parseInt(args[0]);
          }
          else if (args[0].equals("."))
          {
            owner = player.getName();
          }
          else
          {
            owner = args[0];
          }
        }

        if (page < 0) page = 0;
        
        // Sanitize owner name
        if (owner != null)
        {
	        Pattern pattern = Pattern.compile("[^\\w@\\.\\'\\-]");
	        Matcher matcher = pattern.matcher(owner);
	        matcher.replaceAll("");
        }

        // Check permissions
        if (owner == null)
        {
          if (!player.hasPermission("teleplugin.listwarps.global"))
          {
            getLogger().warning(player.getName() + " was not permitted to list global warp points");
            respond(player, MSG_PREFIX + COLOR_WARNING + "You don't have permission to list these warp points");
          }
        }
        else if (owner.equalsIgnoreCase(player.getName()))
        {
          if (!player.hasPermission("teleplugin.listwarps.own"))
          {
            getLogger().warning(player.getName() + " was not permitted to list own warp points");
            respond(player, MSG_PREFIX + COLOR_WARNING + "You don't have permission to list these warp points");
          }
        }
        else
        {
          if (!player.hasPermission("teleplugin.listwarps.other"))
          {
            getLogger().warning(player.getName() + " was not permitted to list " + owner + "'s warp points");
            respond(player, MSG_PREFIX + COLOR_WARNING + "You don't have permission to list these warp points");
          }
        }

        File dir = new File(this.getDataFolder(), "warps");
        if (owner != null)
          dir = new File(dir, owner);

        // List all files ending with ".loc"
        File[] warps = dir.listFiles(new FilenameFilter()
        {
          @Override
          public boolean accept(File dir, String name)
          {
            return new File(dir, name).isFile() && name.toLowerCase().endsWith(".loc");
          }
        });
        
        /* warps is null if the warps/ directory does not exist (e.g. if no warps have been created yet);
         * to avoid NullPointerException, warps is instantiated as an empty array */ 
        if (warps == null)
        	warps = new File[0];

        int pageCount = (int) Math.ceil(warps.length/10f);
        respond(player, MSG_PREFIX + COLOR_INFO + "Warps (page " + (page + 1) + " of " + pageCount + "):");

        int startIndex = page * 10;
        for (int i = startIndex; i < startIndex + 10 && i < warps.length; i++)
          respond(player, MSG_PREFIX + COLOR_INFO + warps[i].getName());

        return true;
      }
      
      return false;
    }

    @EventHandler
    public boolean onLogin( PlayerLoginEvent event ) {
        Player player = event.getPlayer();
        registerLocation(player.getName(), player.getLocation());
    	return true;
    }

    @EventHandler
    public boolean onQuit( PlayerQuitEvent event ) {
    	// Save and unload locations from memory
    	String pname = event.getPlayer().getName();
        registerLocation(pname, event.getPlayer().getLocation());
        saveLocations(pname);
    	locs.remove(pname);
    	return true;
    }

    @EventHandler
    public boolean onMove( PlayerMoveEvent event ) {
        registerLocation(event.getPlayer().getName(), event.getFrom());
    	return true;
    }

    @EventHandler(priority = EventPriority.LOW) // Must process before CreativeControl
    public void onTeleport( PlayerTeleportEvent event ) {
        Player player = event.getPlayer();
        String pname = player.getName();
        registerLocation(pname, event.getFrom());
    	String from_world = event.getFrom().getWorld().getName().toLowerCase();
    	String to_world = event.getTo().getWorld().getName().toLowerCase();
		getLogger().fine("DEBUG: " + pname + " teleporting from '" + from_world + "' to '" + to_world + "'");
    	if (from_world.equals(to_world)) {
    		return;	// Teleporting within the same world always permitted
    	} else {
    		if (player.hasPermission("teleplugin.creative."+to_world)) {
    			// Switch to CREATIVE mode
    			player.setGameMode(GameMode.CREATIVE);
    		} else {
    			// Switch to SURVIVAL mode
    			player.setGameMode(GameMode.SURVIVAL);
    		}
    	}
    	if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
    		getLogger().info(pname + " used a Nether portal");
    		return;	// Allow game mechanic
    	}
    	if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
    		getLogger().info(pname + " used a The End portal");
    		return;	// Allow game mechanic
    	}
    	if (player.hasPermission("teleplugin.enter.any") == false && player.hasPermission("teleplugin.enter." + to_world) == false) {
    		getLogger().info(pname + " was denied access to enter world '" + to_world + "'");
			player.sendMessage(MSG_PREFIX + COLOR_WARNING + "You are not allowed to enter " + to_world + COLOR_WARNING + " this way");
    		event.setCancelled(true);
        	return;
    	}
    	if (player.hasPermission("teleplugin.leave.any") == false && player.hasPermission("teleplugin.leave." + from_world) == false) {
    		getLogger().info(pname + " was denied access to leave world '" + from_world + "'");
			player.sendMessage(MSG_PREFIX + COLOR_WARNING + "You are not allowed to leave " + to_world + COLOR_WARNING + " this way");
    		event.setCancelled(true);
        	return;
    	}
    	return;
    }


    private boolean teleport(String subject, String destination, Boolean force) {
    	Player subj = getServer().getPlayer(subject);
    	Player dest = getServer().getPlayer(destination);
    	if (subject.equals(destination)) {
    		System.out.println("[TP] Teleport "+subject+" to "+destination+"..?");
    		return true;
    	}
    	if (subj != null && dest != null) {
    		Location loc = dest.getLocation();
    		if (safeTeleport(subj, loc, force)) {
        		subj.sendMessage(MSG_PREFIX + COLOR_INFO + "Teleporting you to " + COLOR_NAME + dest.getName());
    			return true;
    		} else {
        		subj.sendMessage(MSG_PREFIX + COLOR_WARNING + "Teleport to " + COLOR_NAME + dest.getName() + COLOR_WARNING + " failed");
    			return false;
    		}
    	} else {
    		if (subj == null) {
    			System.out.println("[TP] Teleport who?");
    		}
    		if (dest == null) {
    			System.out.println("[TP] Teleport where?");
    		}
    		return false;
    	}
    }

    private boolean request_permission(String subject, String destination) {
    	File f = null;
    	long now = System.currentTimeMillis();

    	// Requesting permission to self? Go away.
    	if (subject.equalsIgnoreCase(destination)) {
    		return false;
    	}

    	// Create request token
    	f = new File(req_dir, subject + "-to-" + destination + ".requested");
    	if (f.exists() && f.lastModified() + cooldown > now) {
    		// Already requested so renew quietly
    		getLogger().info(subject + " renewing /tpa request to " + destination);
    		f.setLastModified(now);
    		return true;
    	} else {
    		// This is a new request
    		Player p = this.getServer().getPlayer(destination);
    		if (p != null) {
    			getLogger().info(subject + " sending new /tpa request to " + destination);
	    		try {
					f.createNewFile();
		    		p.sendMessage(MSG_PREFIX + COLOR_NAME + subject + COLOR_INFO + " has requested /tpa permission (See '/tphelp')");
				} catch (IOException e) {
					e.printStackTrace();
					getLogger().warning("Unexpected error creating "+f.getName()+": "+e.getLocalizedMessage());
					return false;
				}
	    		return true;
    		} else {
    			return false;
    		}
    	}
    }

    private boolean cancel_request(String subject, String destination) {
    	File f = null;

    	// Create request token
		f = new File(req_dir, subject + "-to-" + destination + ".requested");
    	f.delete();

    	return true;
    }

    private boolean grant_permission(String subject, String destination) {
    	File f = null;
    	long now = System.currentTimeMillis();

    	// Granting permission to self? Go away.
    	if (subject.equalsIgnoreCase(destination)) {
    		return false;
    	}

    	// Delete denial token, if any
    	f = new File(req_dir, destination + "-to-" + subject + ".denied");
    	f.delete();

    	// Create permission token
    	f = new File(req_dir, destination + "-to-" + subject + ".granted");
    	if (f.exists() && f.lastModified() + cooldown > now) {
    		// Already granted so renew quietly
    		getLogger().info(subject + " renewing /tpa permission for " + COLOR_NAME + destination);
    		f.setLastModified(now);
    		return true;
    	} else {
    		// This is a new permission
    		Player p = this.getServer().getPlayer(destination);
    		if (p != null) {
    			getLogger().info(subject + " granting new /tpa permission for " + destination);
				try {
					f.createNewFile();
		    		p.sendMessage(MSG_PREFIX + COLOR_NAME + subject + COLOR_INFO + " has granted you /tpa permission (See '/tphelp')");

				} catch (IOException e) {
					e.printStackTrace();
					getLogger().warning("Unexpected error creating "+f.getName()+": "+e.getLocalizedMessage());
					return false;
				}
	    		return true;
    		} else {
    			return false;
    		}
    	}
    }

    private boolean deny_permission(String subject, String destination) {
    	File f = null;
    	long now = System.currentTimeMillis();

    	// Denying permission to self? Go away.
    	if (subject.equalsIgnoreCase(destination)) {
    		return false;
    	}

    	// Delete permission token, if any
    	f = new File(req_dir, destination + "-to-" + subject + ".granted");
    	f.delete();

    	// Delete request token, if any
    	f = new File(req_dir, destination + "-to-" + subject + ".requested");
    	f.delete();

    	// Create denial token
    	f = new File(req_dir, destination + "-to-" + subject + ".denied");
    	if (f.exists() && f.lastModified() + cooldown > now) {
    		// Already denied so renew quietly
    		getLogger().info(subject + " renewing /tpa denial for " + destination);
    		f.setLastModified(now);
    		return true;
    	} else {
    		// This is a new denial
    		Player p = this.getServer().getPlayer(destination);
    		getLogger().info(subject + " creating new /tpa denial for " + destination);
			try {
				f.createNewFile();
	    		p.sendMessage(MSG_PREFIX + COLOR_NAME + subject + COLOR_WARNING + " has denied you /tpa permission (See '/tphelp')");
			} catch (IOException e) {
				e.printStackTrace();
				getLogger().warning("Unexpected error creating "+f.getName()+": "+e.getLocalizedMessage());
				return false;
			}
    		return true;
    	}
    }

    private boolean has_denial(String subject, String destination) {
    	File f = null;
    	long now = System.currentTimeMillis();

    	// Checking permission to self? Go away.
    	if (subject.equalsIgnoreCase(destination)) {
    		return false;
    	}

    	// Check for valid permission token
    	f = new File(req_dir, subject + "-to-" + destination + ".denied");
    	if (f.exists() && f.lastModified() + cooldown > now) {
    		return true;
    	} else {
    		return false;
    	}
    }

    private boolean has_permission(String subject, String destination) {
    	File f = null;
    	long now = System.currentTimeMillis();

    	// Checking permission to self? Go away.
    	if (subject.equalsIgnoreCase(destination)) {
    		return false;
    	}

    	// Check for valid permission token
    	f = new File(req_dir, subject + "-to-" + destination + ".granted");
    	if (f.exists() && f.lastModified() + cooldown > now) {
    		return true;
    	} else {
    		return false;
    	}
    }

    private boolean has_request(String subject, String destination) {
    	File f = null;
    	long now = System.currentTimeMillis();

    	// Checking for request to self? Go away.
    	if (subject.equalsIgnoreCase(destination)) {
    		return false;
    	}

    	// Check for valid request token
    	f = new File(req_dir, subject + "-to-" + destination + ".requested");
    	if (f.exists() && f.lastModified() + cooldown > now) {
    		return true;
    	} else {
    		return false;
    	}
    }

    private Integer getUnixtime() {
    	return (int) (System.currentTimeMillis() / 1000L);
    }

	private void registerLocation(String pname, Location loc) {
    	Integer minute_now = (getUnixtime() / 60);
    	Integer minute_limit = minute_now - max_tpback;
    	// Fetch this player's location table
    	ConcurrentHashMap<Integer,Location> playerlocs = locs.get(pname);
    	if (playerlocs == null) {
    		playerlocs = loadLocations(pname);
    		locs.put(pname, playerlocs);
    	}
    	// Unless already done this minute, record current location
    	playerlocs.putIfAbsent(minute_now, loc);
    	// Purge data older than 1 hour
    	for (Integer minute : playerlocs.keySet()) {
    		if (minute > minute_limit) {
    			break;
    		}
    		playerlocs.remove(minute);
    	}
    }

    private Location getLocation(String pname, Integer delta) {
    	Location loc = null;
    	Integer minute_now = (getUnixtime() / 60) - delta;
    	// Fetch this player's location table
    	ConcurrentHashMap<Integer,Location> playerlocs = locs.get(pname);
    	if (playerlocs == null) {
    		//logger.info("[TP] I have no data for "+pname);
    		return loc;
    	}
    	// Play back the last hour until we get past the moment we're looking for
		//logger.info("[TP] Searching "+pname+"'s CHM for delta "+minutes+" (unixtime "+unixtime+")");
    	List<Integer> keys = new ArrayList<Integer>(playerlocs.keySet());
    	Collections.sort(keys);
    	for (Integer minute : keys) {
    		if (minute > minute_now) {
        		//logger.info("[TP] "+pname+"'s next location is at "+minute+" which is past delta.");
    			break;
    		}
    		//logger.info("[TP] Found "+pname+"'s location at "+minute);
    		loc = playerlocs.get(minute);
    	}
    	return loc;
    }

    private Integer getOldestDelta(String pname) {
    	// Fetch this player's location table
    	ConcurrentHashMap<Integer,Location> playerlocs = locs.get(pname);
    	if (playerlocs == null) {
    		return null;
    	}
    	List<Integer> keys = new ArrayList<Integer>(playerlocs.keySet());
    	Collections.sort(keys);
    	for (Integer minute : keys) {
    		// Don't actually loop, just return the first one
    		//logger.info("[TP] Found "+pname+"'s location at "+minute);
        	return (getUnixtime()/60) - minute;
    	}
    	return null; // Unreachable
    }

    private ConcurrentHashMap<Integer,Location> loadLocations(String pname) {
    	Integer minute_now = (getUnixtime() / 60);
    	Integer minute_limit = minute_now - max_tpback;
    	ConcurrentHashMap<Integer,Location> playerlocs = new ConcurrentHashMap<Integer,Location>();
    	File file = new File(loc_dir, pname + ".dat");
    	if (file.exists() == false) {
    		return playerlocs;
    	}
        BufferedReader reader = null;
        try {
			reader = new BufferedReader(new FileReader(file));
			String line = null;
			while ((line = reader.readLine()) != null) {
				String[] pair = line.split(":");
				Integer minute = Integer.valueOf(pair[0]);
				if (minute >= minute_limit) {
					// Data is recent enough to keep
					String[] values = pair[1].split(",");
					World world = getServer().getWorld(values[0]);
					if (world != null) {
						// World still exists
						Double x = Double.valueOf(values[1]);
						Double y = Double.valueOf(values[2]);
						Double z = Double.valueOf(values[3]);
						Float yaw = Float.valueOf(values[4]);
						Float pitch = Float.valueOf(values[5]);
						Location loc = new Location(world, x, y, z, yaw, pitch);
						// Remember this location
						playerlocs.put(minute, loc);
					}
				}
			}
		} catch (FileNotFoundException e) {
			// Should not be possible
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    	return playerlocs;
    }

    private void saveLocations(String pname) {
		ConcurrentHashMap<Integer,Location> playerlocs = locs.get(pname);
		if (playerlocs == null) {
			getLogger().warning("Internal error: No location data to save for player "+pname);
			return;
		}
    	File file = new File(loc_dir, pname + ".dat");
    	try {
			FileWriter outFile = new FileWriter(file);
			PrintWriter out = new PrintWriter(outFile);
			for (Integer minute : playerlocs.keySet()) {
				Location loc = playerlocs.get(minute);
				// Serialize location manually
				String w = loc.getWorld().getName();
				Double x = loc.getX();
				Double y = loc.getY();
				Double z = loc.getZ();
				Float yaw = loc.getYaw();
				Float pitch = loc.getPitch();
				// Write to file
				out.println(minute+":"+w+","+x+","+y+","+z+","+yaw+","+pitch);
			}
			out.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
    }

	private boolean safeTeleport(Player player, Location location, Boolean force) {
		if (location == null) {
			player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Invalid location");
			return false;
		}
		Integer x = location.getBlockX();
		Integer y = location.getBlockY();
		Integer z = location.getBlockZ();
		Block b = null;
		World world = location.getWorld();
		if (y < world.getMaxHeight()) { y++; }
		Integer needAir = 2;
		Boolean danger = false;
		for (Integer check = y; check >= 0; check--) {
			b = world.getBlockAt(x, check, z);
			Material type = b.getType();

			if (isAir(type)) {
				needAir--;
				continue;
			}
			if (type == Material.LAVA || type == Material.STATIONARY_LAVA) {
				player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Lava detected");
				danger = true;
				break;
			}
			if (type == Material.FIRE) {
				player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Fire detected");
				danger = true;
			}
			if (type == Material.TRAP_DOOR) {
				player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Trapdoor detected");
				danger = true;
				break;
			}
			if (needAir > 0) {
				player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Blocked location detected ("+b.getType().name()+")");
				danger = true;
			}
			break; // Found a safe platform
		}
		if (isPVP(location)) {
			player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Player vs Player (PVP) region detected");
			danger = true;
		}
		if (danger == false) {
			return player.teleport(location);
		} else if (force == true) {
			getLogger().info(player.getName() + " used the safety override");
			player.sendMessage(MSG_PREFIX + COLOR_WARNING + "Safety override (-force) is in effect");
			return player.teleport(location);
		}
		return false;
	}

	private boolean isAir(Material type) {
		boolean ret = !type.isSolid();
		
		// exceptions
		switch (type)
		{
			case WATER:
			case STATIONARY_WATER:
			case LAVA:
			case STATIONARY_LAVA:
			case PORTAL:
			// Add more non-solid materials that do NOT allow breathing here...
				ret = false;
				break;
			case PISTON_EXTENSION:
			case PISTON_MOVING_PIECE:
			case STEP:
			case SIGN_POST:
			case WOODEN_DOOR:
			case WALL_SIGN:
			case STONE_PLATE:
			case IRON_DOOR_BLOCK:
			case WOOD_PLATE:
			case FENCE:
			case CAKE_BLOCK:
			case TRAP_DOOR:
			case IRON_FENCE:
			case THIN_GLASS:
			case FENCE_GATE:
			case NETHER_FENCE:
			case BREWING_STAND:
			case CAULDRON:
			case WOOD_STEP:
			case GOLD_PLATE:
			case IRON_PLATE:
			// Add more solid materials that do allow breathing here...
				ret = true;
				break;
			default:
				break;
		}

        return ret;
	}

	private String time_to_delta(String timestr) {
		String[] parts = timestr.split(":");
		Integer hh = Integer.parseInt(parts[0]);
		Integer mm = Integer.parseInt(parts[1]);

		// Clamp values
		if (hh < 0) { hh += 24; }
		if (hh > 23) { hh -= 24; }
		if (mm < 0) { mm += 60; }
		if (mm > 59) { hh -= 60; }

		// Use Calendar to calculate the difference
		Calendar now = Calendar.getInstance();
		Calendar then = Calendar.getInstance();
		then.set(Calendar.HOUR_OF_DAY, hh);
		then.set(Calendar.MINUTE, mm);
		long ms = now.getTimeInMillis() - then.getTimeInMillis();
		//getLogger().info("The time is now "+now);
		//getLogger().info("The time you requested (hours="+hh+", minutes="+mm+") I interpret as "+then);
		//getLogger().info("The difference is "+ms+" milliseconds");
		if (ms < 0) {
			// Requested time is in the future so we must assume the user means yesterday
			//getLogger().info("No, that's in the future, let's go one day back.");
			then.add(Calendar.DATE, -1);
			//getLogger().info("The time you requested (hours="+hh+", minutes="+mm+") I interpret as "+then);
			ms = now.getTimeInMillis() - then.getTimeInMillis();
			//getLogger().info("The difference is "+ms+" milliseconds");
		}

		// Copnvert from milliseconds to minutes and return to caller
		Integer delta = (int) ms/(1000*60);
		//getLogger().info("Or "+delta+" minutes");
		return delta.toString();
	}

	private Integer numOptions(String[] array) {
		Integer options = 0;
		for (String str : array) {
			if (str.startsWith("--")) { options++; }
		}
		return options;
	}

	private void respond(Player p, String msg) {
		if (p != null) {
			p.sendMessage(msg);
		} else {
	    	Server server = getServer();
	    	ConsoleCommandSender console = server.getConsoleSender();
			console.sendMessage(msg);
		}
	}


	private boolean isPVP(Location loc) {
		if (worldguard == null) { return false; }
		
		RegionManager regionManager = worldguard.getRegionContainer().get(loc.getWorld());
		ApplicableRegionSet set = regionManager.getApplicableRegions(loc);
		return set.testState(null, DefaultFlag.PVP);
	}

}
