package com.bencodez.simpleapi.file;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import lombok.Getter;
import lombok.Setter;

/**
 * Retains the SimpleAPI 0.0.7 JSON cache contract while using the Gson parser
 * API supplied by legacy Java 8 proxy servers. Source pinned to
 * 1617356c3f026f741b5562bf62c8de59345637e1; no cache-format changes.
 */
public class BungeeJsonFile {
	@Getter
	@Setter
	private JsonObject conf;

	@Getter
	private File file;

	private Gson gson;

	public BungeeJsonFile(File file) {
	    this.file = file;
	    this.gson = new GsonBuilder().setPrettyPrinting().create();

	    if (!file.exists()) {
	        try {
	            File parentDir = file.getParentFile();
	            if (parentDir != null && !parentDir.exists()) {
	                parentDir.mkdirs();
	            }
	            file.createNewFile();
	            conf = new JsonObject();
	            save(); // Save file with empty JsonObject upon creation
	        } catch (IOException e) {
	            e.printStackTrace();
	        }
	    } else {
	        try (FileReader reader = new FileReader(file)) {
	            conf = new JsonParser().parse(reader).getAsJsonObject();
	        } catch (com.google.gson.JsonSyntaxException e) {
	            System.err.println("Error parsing JSON file: " + e.getMessage());
	            conf = attemptPartialRecovery(file); // Attempt to recover as much as possible
	        } catch (IOException e) {
	            e.printStackTrace();
	            conf = new JsonObject(); // Fallback to an empty JsonObject
	        }
	    }
	}

	private JsonObject attemptPartialRecovery(File file) {
	    JsonObject recoveredData = new JsonObject();
	    try (FileReader fileReader = new FileReader(file);
	         com.google.gson.stream.JsonReader jsonReader = new com.google.gson.stream.JsonReader(fileReader)) {
	        jsonReader.setLenient(true); // Allow lenient parsing
	        recoveredData = new JsonParser().parse(jsonReader).getAsJsonObject();
	        System.err.println("Partial recovery of JSON data succeeded.");
	    } catch (Exception ex) {
	        System.err.println("Failed to recover JSON data: " + ex.getMessage());
	    }
	    return recoveredData;
	}

	private JsonObject navigateToNode(String path) {
		String[] parts = path.split("\\.");
		JsonObject current = conf;

		// Handle a single-part path separately
		if (parts.length == 1) {
			if (current.has(parts[0]) && current.get(parts[0]).isJsonObject()) {
				return current.getAsJsonObject(parts[0]);
			} else {
				// System.out.println("navigateToNode: Invalid path component (single-path): " +
				// parts[0]);
				return null;
			}
		}

		// Iterate over path parts, ending before the last
		for (int i = 0; i < parts.length - 1; i++) {
			JsonElement element = current.get(parts[i]);
			if (element != null && element.isJsonObject()) {
				current = element.getAsJsonObject();
			} else {
				// System.out.println("navigateToNode: Invalid path component: " + parts[i]);
				return null;
			}
		}

		// Return the final parent object
		return current;
	}

	private JsonObject ensureParentObjectsExist(String path) {
		String[] parts = path.split("\\.");
		JsonObject current = conf;

		// Iterate through path parts except the last part, which is the actual key
		for (int i = 0; i < parts.length - 1; i++) {
			if (!current.has(parts[i]) || current.get(parts[i]) == null || !current.get(parts[i]).isJsonObject()) {
				// Create a new JsonObject if none exists or it's not an object
				current.add(parts[i], new JsonObject());
			}
			current = current.getAsJsonObject(parts[i]);
		}

		return current;
	}

	private String getLastPathPart(String path) {
		String[] parts = path.split("\\.");
		return parts[parts.length - 1];
	}

	public boolean getBoolean(String path, boolean def) {
		JsonObject node = navigateToNode(path);
		String lastPart = getLastPathPart(path);
		return node != null && node.has(lastPart) ? node.get(lastPart).getAsBoolean() : def;
	}

	public int getInt(String path, int def) {
		JsonObject node = navigateToNode(path);
		String lastPart = getLastPathPart(path);
		return node != null && node.has(lastPart) ? node.get(lastPart).getAsInt() : def;
	}

	public long getLong(String path, long def) {
		JsonObject node = navigateToNode(path);
		String lastPart = getLastPathPart(path);
		return node != null && node.has(lastPart) ? node.get(lastPart).getAsLong() : def;
	}

	public String getString(String path, String def) {
		JsonObject node = navigateToNode(path);
		String lastPart = getLastPathPart(path);
		return node != null && node.has(lastPart) ? node.get(lastPart).getAsString() : def;
	}

	public List<String> getStringList(String path, List<String> def) {
		JsonObject node = navigateToNode(path);
		String lastPart = getLastPathPart(path);
		if (node != null && node.has(lastPart)) {
			List<String> list = new ArrayList<>();
			for (JsonElement element : node.getAsJsonArray(lastPart)) {
				list.add(element.getAsString());
			}
			return list;
		}
		return def;
	}

	public List<String> getKeys(String path) {
		JsonObject parentNode = navigateToNode(path);
		String lastPart = getLastPathPart(path);
		JsonObject node = null;
		if (path.split("\\.").length == 1) {
			node = parentNode;
		} else if (parentNode != null) {
			node = parentNode.getAsJsonObject(lastPart);
		}

		if (node != null) {
			List<String> keys = new ArrayList<>();
			for (Map.Entry<String, JsonElement> entry : node.entrySet()) {
				keys.add(entry.getKey());
			}
			return keys;
		}
		return new ArrayList<>();
	}

	public JsonElement getNode(String path) {
		JsonObject parentNode = navigateToNode(path);
		String lastPart = getLastPathPart(path);

		// If the path is a single component, handle it directly
		if (parentNode == null && conf.has(lastPart)) {
			return conf.get(lastPart);
		}

		if (parentNode != null && parentNode.has(lastPart)) {
			return parentNode.get(lastPart);
		}

		// System.out.println("getNode: Could not find element for path = " + path);
		return null;
	}

	public synchronized void setInt(String path, int value) {
		JsonObject node = ensureParentObjectsExist(path);
		String lastPart = getLastPathPart(path);
		node.addProperty(lastPart, value);
	}

	public synchronized void setString(String path, String value) {
		JsonObject node = ensureParentObjectsExist(path);
		String lastPart = getLastPathPart(path);
		node.addProperty(lastPart, value);
	}

	public synchronized void setBoolean(String path, boolean value) {
		JsonObject node = ensureParentObjectsExist(path);
		String lastPart = getLastPathPart(path);
		node.addProperty(lastPart, value);
	}

	public synchronized void setLong(String path, long value) {
		JsonObject node = ensureParentObjectsExist(path);
		if (node != null) {
			String lastPart = getLastPathPart(path);
			node.addProperty(lastPart, value);
		}
	}

	public synchronized void setStringList(String path, List<String> value) {
		JsonObject node = ensureParentObjectsExist(path);
		if (node != null) {
			String lastPart = getLastPathPart(path);
			JsonArray jsonArray = new JsonArray();
			for (String item : value) {
				jsonArray.add(item);
			}
			node.add(lastPart, jsonArray);
		}
	}

	public synchronized void remove(String path) {
	    if (!path.contains(".")) {
	        conf.remove(path);
	        return;
	    }

	    JsonObject node = navigateToNode(path);
	    if (node != null) {
	        String lastPart = getLastPathPart(path);
	        node.remove(lastPart);
	    }
	}

	public void reload() {
		if (file.exists()) {
			try (FileReader reader = new FileReader(file)) {
				conf = new JsonParser().parse(reader).getAsJsonObject();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public synchronized void save() {
		try (FileWriter writer = new FileWriter(file)) {
			gson.toJson(conf, writer);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
