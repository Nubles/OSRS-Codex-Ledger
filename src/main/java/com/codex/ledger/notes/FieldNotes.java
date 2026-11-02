package com.codex.ledger.notes;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.runelite.client.util.Filepath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Field notes: an optional, local record of what Ledger read and decided, one JSON object a line,
 * written to the plugin's own folder under .runelite/plugin-data through RuneLite's Filepath,
 * which keeps every write inside that folder. It exists so the plugin's guesses can be checked
 * against real play. Off unless the player turns it on; nothing is sent anywhere.
 */
public final class FieldNotes
{
	private static final Logger log = LoggerFactory.getLogger(FieldNotes.class);
	/** A notes file stops growing past this, so a forgotten setting cannot fill a disk. */
	private static final long LIMIT = 25L * 1024 * 1024;

	/** The plugin's data folder, looked up only when notes are first opened. */
	private final Callable<Filepath> dir;
	private final Gson gson;
	private final ExecutorService writer = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "codex-ledger-notes");
		t.setDaemon(true);
		return t;
	});
	private BufferedWriter out;
	private Filepath file;
	private long written;
	private volatile boolean active;
	private final java.util.Map<String, String> last = new java.util.HashMap<>();

	public FieldNotes(Callable<Filepath> dir, Gson gson)
	{
		this.dir = dir;
		this.gson = gson;
	}

	/** Opens a new notes file. Safe to call when already open. */
	public synchronized void open(String version)
	{
		if (active)
		{
			return;
		}
		active = true;
		synchronized (last)
		{
			last.clear();
		}
		String name = "field-notes-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".jsonl";
		writer.execute(() ->
		{
			try
			{
				Filepath folder = dir.call();
				folder.createDirectories();
				Filepath target = folder.joinSegment(name);
				BufferedWriter w = target.openBufferedWriter(StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				synchronized (this)
				{
					out = w;
					file = target;
					written = 0;
				}
			}
			catch (Exception e)
			{
				log.warn("Could not open field notes {}", name, e);
			}
		});
		JsonObject start = new JsonObject();
		start.addProperty("plugin", "codex-ledger");
		start.addProperty("version", version);
		start.addProperty("java", System.getProperty("java.version"));
		note("start", start);
	}

	/** Opens a new notes file without a version. */
	public void open()
	{
		open(null);
	}

	/** Adds a line built from an object's plain fields; nothing happens while notes are off. */
	public void note(String kind, Object body)
	{
		if (active)
		{
			note(kind, body instanceof JsonObject ? (JsonObject) body : tree(body));
		}
	}

	/** Adds a line only when it differs from the last one of its kind, for things read again and again. */
	public void noteChanged(String kind, JsonObject body)
	{
		if (!active)
		{
			return;
		}
		String key = body.toString();
		synchronized (last)
		{
			if (key.equals(last.get(kind)))
			{
				return;
			}
			last.put(kind, key);
		}
		note(kind, body);
	}

	public boolean isOpen()
	{
		return active;
	}

	/** The file being written, once it is open, or null. */
	public synchronized Filepath file()
	{
		return file;
	}

	/** Adds a line. The time and the kind of note lead every line. */
	public void note(String kind, JsonObject body)
	{
		if (!active)
		{
			return;
		}
		JsonObject line = new JsonObject();
		line.addProperty("ms", System.currentTimeMillis());
		line.addProperty("k", kind);
		for (String key : body.keySet())
		{
			line.add(key, body.get(key));
		}
		String text = gson.toJson(line);
		writer.execute(() -> write(text));
	}

	/** Turns an object with plain fields, such as a planner's snapshot, into a note body. */
	public JsonObject tree(Object o)
	{
		return o == null ? new JsonObject() : gson.toJsonTree(o).getAsJsonObject();
	}

	/**
	 * An object as JSON for a note, or its plain text when it can't be turned into JSON (a field type
	 * Gson can't reach on this Java), so a note can never break the plugin.
	 */
	public JsonElement safe(Object o)
	{
		if (o == null)
		{
			return JsonNull.INSTANCE;
		}
		try
		{
			return gson.toJsonTree(o);
		}
		catch (RuntimeException | StackOverflowError e)
		{
			return new JsonPrimitive(String.valueOf(o));
		}
	}

	/** Finishes the file. Safe to call when already closed. */
	public void close()
	{
		active = false;
		writer.execute(() ->
		{
			synchronized (this)
			{
				if (out == null)
				{
					return;
				}
				try
				{
					out.close();
				}
				catch (IOException e)
				{
					log.warn("Could not close field notes", e);
				}
				out = null;
				file = null;
			}
		});
	}

	public void shutdown()
	{
		close();
		writer.shutdown();
	}

	private synchronized void write(String text)
	{
		if (out == null || written > LIMIT)
		{
			return;
		}
		try
		{
			out.write(text);
			out.newLine();
			out.flush();
			written += text.length() + 1;
		}
		catch (IOException e)
		{
			log.warn("Could not write field notes", e);
		}
	}
}
