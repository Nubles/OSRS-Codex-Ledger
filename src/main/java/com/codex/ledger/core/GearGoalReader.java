package com.codex.ledger.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Codex Gear Roadmap's savings goal, as it saves it to the RuneScape profile under
 * {@code codexgearroadmap.goal}: the upgrade's name and one item id per piece. Read and never written;
 * another plugin's format, so anything that cannot be read is no goal rather than a guess.
 */
public final class GearGoalReader
{
	public static final String GROUP = "codexgearroadmap";
	public static final String KEY = "goal";

	private GearGoalReader()
	{
	}

	/** The goal's name and piece ids. */
	public static final class Goal
	{
		private final String name;
		private final List<Integer> ids;

		Goal(String name, List<Integer> ids)
		{
			this.name = name;
			this.ids = Collections.unmodifiableList(ids);
		}

		public String getName()
		{
			return name;
		}

		public List<Integer> getIds()
		{
			return ids;
		}
	}

	/** The saved goal, or null when there is none or it cannot be read. */
	public static Goal read(Gson gson, String json)
	{
		if (json == null || json.trim().isEmpty())
		{
			return null;
		}
		JsonObject o;
		try
		{
			JsonElement e = gson.fromJson(json, JsonElement.class);
			if (e == null || !e.isJsonObject())
			{
				return null;
			}
			o = e.getAsJsonObject();
		}
		catch (RuntimeException e)
		{
			return null;
		}
		JsonElement name = o.get("name");
		JsonElement list = o.get("ids");
		if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString() || name.getAsString().trim().isEmpty()
			|| list == null || !list.isJsonArray())
		{
			return null;
		}
		List<Integer> ids = new ArrayList<>();
		for (JsonElement e : list.getAsJsonArray())
		{
			if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber())
			{
				double d = e.getAsDouble();
				if (d == Math.rint(d) && d > 0 && d <= Integer.MAX_VALUE && !ids.contains((int) d))
				{
					ids.add((int) d);
				}
			}
		}
		return ids.isEmpty() ? null : new Goal(name.getAsString().trim(), ids);
	}
}
