package no.atc.floyd.bukkit.tele;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class Warp {
	
	private String ownername = "";
	private String pointname = "";
	private String worldname = "";
	private Server server = null;
	private Plugin plugin = null;
	private Location loc = null;
	private Boolean global = true;
	private String lasterror = "";
	
	public Warp(String name, Player p, Plugin plu) {
		String[] parts;
		// Get essentials
		plugin = plu;
		server = plu.getServer();

		// Sanitize name
		Pattern pattern = Pattern.compile("[^\\w@\\.\\'\\-]");
		Matcher matcher = pattern.matcher(name);
		matcher.replaceAll("");

		if (p != null) {
			loc = p.getLocation();
		}
		
		// If owner is specified, owner and point are separated by "."
		parts = name.split("\\.", 2);
		if (parts.length == 1) {
			pointname = parts[0];
		} else {
			global = false;
			ownername = parts[0];
			pointname = parts[1];
			if (ownername.equals("")) {
				// If console uses .<location> then we quietly ignore the "."
				if (p != null) {
					ownername = p.getName();
				}
			}
		}
		
		// If world is specified, point and world are separated by "@"
		parts = pointname.split("\\@", 2);
		if (parts.length == 2) {
			pointname = parts[0];
			worldname = parts[1];
		}
		plugin.getLogger().finest("final: ownername="+ownername+" pointname="+pointname+" worldname="+worldname);
	}
	
	public String error() {
		return lasterror;
	}
	
	public boolean exists() {
		// Return true if the warp name can be resolved to a location
		File f = this.file();
		if (f.exists()) {
			plugin.getLogger().finest(f + " exists");
		} else {
			plugin.getLogger().finest(f + " does not exist");
		}
		return f.exists();
	}

	public boolean usePermitted(Player p) {
		// Return true if player has permission to use this warp name
		if (p == null) {
			return true;
		}
		if (global) {
			return p.hasPermission("teleplugin.warp.global");
		}
		if (ownername.equalsIgnoreCase(p.getName())) {
			return p.hasPermission("teleplugin.warp.own");
		} else {
			return p.hasPermission("teleplugin.warp.other");
		}
	}

	public boolean createPermitted(Player p) {
		// Return true if player has permission to create this warp name
		if (p == null) {
			return true;
		}
		if (global) {
			return p.hasPermission("teleplugin.setwarp.global");
		}
		if (ownername.equalsIgnoreCase(p.getName())) {
			return p.hasPermission("teleplugin.setwarp.own");
		} else {
			return p.hasPermission("teleplugin.setwarp.other");
		}
	}

	public boolean deletePermitted(Player p) {
		// Return true if player has permission to delete this warp name
		if (p == null) {
			return true;
		}
		if (global) {
			return p.hasPermission("teleplugin.delwarp.global");
		}
		if (ownername.equalsIgnoreCase(p.getName())) {
			return p.hasPermission("teleplugin.delwarp.own");
		} else {
			return p.hasPermission("teleplugin.delwarp.other");
		}
	}

	public boolean movePermitted(Player p) {
		// Return true if player has permission to move this warp name
		if (p == null) {
			return true;
		}
		if (global) {
			return p.hasPermission("teleplugin.movewarp.global");
		}
		if (ownername.equalsIgnoreCase(p.getName())) {
			return p.hasPermission("teleplugin.movewarp.own");
		} else {
			return p.hasPermission("teleplugin.movewarp.other");
		}
	}

	public Location location() {
		// Return the location that this warp name refers to
		Location loaded = null;
		String line;
		try {
        	BufferedReader input = new BufferedReader(new FileReader(this.file());
        	line = input.readLine();
        	input.close();
			String[] parts = line.split(":");
			Double x = Double.parseDouble(parts[0]);
			Double y = Double.parseDouble(parts[1]);
			Double z = Double.parseDouble(parts[2]);
			Float yaw = 0f;
			if (parts.length >= 4) {
				yaw = Float.parseFloat(parts[3]);
			}
			Float pitch = 0f;
			if (parts.length >= 5) {
				pitch = Float.parseFloat(parts[4]);
			}
			World w = null;
			if (parts.length == 6) {
				w = server.getWorld(parts[5]);
			}
			if (w == null) {
				w = server.getWorld("world");
			}
			loaded = new Location(w, x, y, z, yaw, pitch);

		} catch (FileNotFoundException e) {
			e.printStackTrace();
			lasterror = e.getLocalizedMessage();
			return null;
		} catch (IOException e) {
			e.printStackTrace();
			lasterror = e.getLocalizedMessage();
			return null;
		} catch (Exception e) {
			e.printStackTrace();
			lasterror = e.getLocalizedMessage();
			return null;
		}
		
		// Override world?
		if (!worldname.equals("")) {
			World w = server.getWorld(worldname);
			if (w == null) {
				return null;
			} else {
				loaded.setWorld(w);
			}
		}
		
		return loaded;
	}

	@Deprecated
	public String fname() {
		return this.file().toString();
	}
	
	public File file() {
		if (ownername.equals("")) {
			// Global warp point
			return new File(plugin.warp_dir, pointname.toLowerCase() + ".loc");
		} else {
			// Personal warp point
			return new File(new File(plugin.warp_dir, ownername.toLowerCase()), pointname.toLowerCase() + ".loc");
		}
	}
	
	public String name() {
		// Return the fully qualified name of this warp
		if (ownername.equals("")) {
			// Global warp point
			return pointname.toLowerCase();
		} else {
			return ownername.toLowerCase()+"."+pointname.toLowerCase();
		}
	}
	
	public boolean create() {
		// Write this warp point to disk
		File f = this.file();
   		String newline = System.getProperty("line.separator");
		try {
			f.getParentFile().mkdirs();
			if (f.createNewFile()) {
	       		BufferedWriter output = new BufferedWriter(new FileWriter(f);
           		output.write( loc.getX() + ":" + loc.getY() + ":" + loc.getZ() + ":" + loc.getYaw() + ":" + loc.getPitch() + ":" + loc.getWorld().getName() + newline );
           		output.close();
    			plugin.getLogger().finest(f + " saved");
			}
			return true;
		} catch (IOException e) {
			e.printStackTrace();
			lasterror = e.getLocalizedMessage();
		}
		return false;
	}

	public boolean delete() {
		// Delete this warp point from disk
		File f = this.file();
		if (f.exists()) {
			f.delete();
			plugin.getLogger().finest(f + " deleted");
			return true;
		} else {
			return false;
		}
	}

	public void touch() {
		// Update the 'modified' time stamp of this warp point
		File f = this.file();
		if (f.exists()) {
			f.setLastModified(System.currentTimeMillis());
			plugin.getLogger().finest(f + " touched");
		}
		return;
	}
}
