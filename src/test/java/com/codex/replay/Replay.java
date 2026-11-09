package com.codex.replay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.api.Experience;
import net.runelite.api.GameState;
import net.runelite.api.Skill;

/**
 * Plays a replay script against a plugin, tick by tick, and checks what the plugin shows.
 *
 * <p>A script is a text file, one command a line; {@code #} starts a comment and quotes keep
 * spaces together. Game ids are written by their RuneLite names ({@code ObjectID},
 * {@code ItemID}, {@code VarbitID} and {@code InventoryID} in {@code net.runelite.api.gameval})
 * or as numbers. The commands:
 *
 * <pre>
 * include /path/file            play another file's commands here (a shared layout, say)
 * scene X Y [PLANE]              load a 104-tile scene with its south-west corner here (clears it)
 * at X Y [PLANE]                 put the player on a tile
 * walk X Y                       walk there a tile a tick, a tick each step
 * height X Y W L H [PLANE]       ground height of a block of tiles (lower is higher up)
 * block X Y W L [PLANE]          make a block of tiles impassable; open X Y W L undoes it
 * object ID X Y [W L] [as NAME]  spawn a game object; wall ID X Y [as NAME] spawns a wall object
 * remove NAME|ID                 despawn an object or NPC by name, or every object with an id
 * multiloc ID VARBIT SHOWN...    an object that shows SHOWN[value of VARBIT] (urns, doors)
 * name ID "Name"                 what the game calls an object (banks are found by name)
 * npc ID "Name" X Y [SIZE] [level L] [as NAME]  an NPC (remove NAME despawns it)
 * transform NAME ID              an NPC turns into another (a portal's shield dropping)
 * player "Name" X Y              another player; actors clears every NPC and player
 * skill SKILL LEVEL [XP]         real level (and experience, else the level's minimum)
 * boost SKILL LEVEL / xp SKILL XP
 * varbit ID VALUE / varp ID VALUE
 * item ITEM "Name" [price N] [stacks]  name an item (and its price) for plugins that read them
 * items INV clear                empty an inventory (INV, WORN, BANK)
 * items INV ITEM COUNT [stacked] add COUNT of an item, one a slot unless stacked
 * widget ID [child N] text "T" | hidden true|false | width N | item ID QTY
 *                                show an interface component (InterfaceID.Group.COMPONENT) and set it
 * close ID                       close an interface component
 * world N [TYPE...]              the world the player is on, and its types (SEASONAL, PVP...)
 * chat TYPE "message"            a chat message (GAMEMESSAGE, SPAM, ...)
 * state GAME_STATE               LOGGED_IN, LOADING, HOPPING, LOGIN_SCREEN
 * tick [N]                       run the plugin N ticks (1)
 * expect KEY OP VALUE            check the last tick's reading: OP is =, !=, ~ (contains) or !~
 * never KEY OP VALUE             from now, fail on any tick where this holds
 * always KEY OP VALUE            from now, fail on any tick where this does not
 * forget                         drop every never and always
 * </pre>
 *
 * Anything else goes to the plugin's {@link Rig}, for its settings and saved state. A reading
 * the plugin did not give this tick is {@code -}.
 *
 * <p>Shared by every Codex plugin: keep this file identical across repositories.
 */
public final class Replay
{
	/** Each game tick moves the wall clock on this much. */
	public static final long TICK_MS = 600;
	/** The wall clock at the first tick: 2026-01-01, so dates and time zones never get in the way. */
	public static final long START = 1767225600000L;

	private final String name;
	private final FakeGame game = new FakeGame();
	private final Rig rig;
	private Map<String, String> readings = new TreeMap<>();
	private final List<Rule> rules = new ArrayList<>();
	private int line;
	/** The file being played: the script, or one it includes. */
	private String file;
	private long now = START;

	private Replay(String name, Function<FakeGame, Rig> rig)
	{
		this.name = name;
		this.rig = rig.apply(game);
		game.listen(this.rig);
	}

	/** Plays a script from the test classpath, such as {@code /replays/ladder.replay}. */
	public static void play(String resource, Function<FakeGame, Rig> rig)
	{
		new Replay(resource, rig).run(resource, read(resource));
	}

	private static List<String> read(String resource)
	{
		InputStream in = Replay.class.getResourceAsStream(resource);
		if (in == null)
		{
			throw new IllegalArgumentException("No replay " + resource);
		}
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
		{
			return r.lines().collect(Collectors.toList());
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
	}

	/** Every {@code .replay} script in a classpath folder, such as {@code /replays}, by resource name. */
	public static List<String> scripts(String folder)
	{
		URL url = Replay.class.getResource(folder);
		if (url == null)
		{
			return new ArrayList<>();
		}
		try (Stream<Path> files = Files.list(Paths.get(url.toURI())))
		{
			return files.map(p -> p.getFileName().toString())
				.filter(n -> n.endsWith(".replay"))
				.sorted()
				.map(n -> folder + "/" + n)
				.collect(Collectors.toList());
		}
		catch (IOException | URISyntaxException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private void run(String file, List<String> lines)
	{
		String outerFile = this.file;
		int outerLine = line;
		this.file = file;
		for (line = 1; line <= lines.size(); line++)
		{
			List<String> words = words(lines.get(line - 1));
			if (words.isEmpty())
			{
				continue;
			}
			if (words.get(0).equals("include"))
			{
				run(words.get(1), read(words.get(1)));
				continue;
			}
			try
			{
				command(words.get(0), words.subList(1, words.size()));
			}
			catch (AssertionError e)
			{
				throw e;
			}
			catch (RuntimeException e)
			{
				throw new IllegalStateException(where() + e, e);
			}
		}
		this.file = outerFile;
		line = outerLine;
	}

	private void command(String verb, List<String> a)
	{
		switch (verb)
		{
			case "scene":
				game.scene(num(a, 0), num(a, 1), a.size() > 2 ? num(a, 2) : 0);
				break;
			case "at":
				game.at(num(a, 0), num(a, 1), a.size() > 2 ? num(a, 2) : 0);
				break;
			case "walk":
				walk(num(a, 0), num(a, 1));
				break;
			case "height":
				game.height(num(a, 0), num(a, 1), num(a, 2), num(a, 3), plane(a, 5), num(a, 4));
				break;
			case "block":
				game.block(num(a, 0), num(a, 1), num(a, 2), num(a, 3), plane(a, 4));
				break;
			case "open":
				game.open(num(a, 0), num(a, 1), num(a, 2), num(a, 3), plane(a, 4));
				break;
			case "object":
			case "wall":
				spawn(verb, a);
				break;
			case "remove":
				remove(a.get(0));
				break;
			case "multiloc":
			{
				int[] shown = new int[a.size() - 2];
				for (int i = 2; i < a.size(); i++)
				{
					shown[i - 2] = id("ObjectID", a.get(i));
				}
				game.multiloc(id("ObjectID", a.get(0)), id("VarbitID", a.get(1)), shown);
				break;
			}
			case "name":
				game.name(id("ObjectID", a.get(0)), a.get(1));
				break;
			case "npc":
			{
				List<String> args = new ArrayList<>(a);
				String handle = take(args, "as");
				String level = take(args, "level");
				game.npc(id("NpcID", args.get(0)), args.get(1), num(args, 2), num(args, 3), game.at() == null ? 0 : game.at().getPlane(),
					args.size() > 4 ? num(args, 4) : 1, level == null ? 0 : Integer.parseInt(level), handle);
				break;
			}
			case "transform":
				game.transform(a.get(0), id("NpcID", a.get(1)));
				break;
			case "player":
				game.player(a.get(0), num(a, 1), num(a, 2), game.at() == null ? 0 : game.at().getPlane());
				break;
			case "actors":
				game.clearActors();
				break;
			case "skill":
			{
				Skill s = Skill.valueOf(a.get(0));
				int level = num(a, 1);
				game.skill(s, level, a.size() > 2 ? num(a, 2) : Experience.getXpForLevel(Math.max(1, Math.min(99, level))));
				break;
			}
			case "boost":
				game.boost(Skill.valueOf(a.get(0)), num(a, 1));
				break;
			case "xp":
				game.xp(Skill.valueOf(a.get(0)), num(a, 1));
				break;
			case "varbit":
				game.varbit(id("VarbitID", a.get(0)), num(a, 1));
				break;
			case "varp":
				game.varp(id("VarPlayerID", a.get(0)), num(a, 1));
				break;
			case "item":
			{
				List<String> args = new ArrayList<>(a);
				String price = take(args, "price");
				boolean stacks = args.remove("stacks");
				game.item(id("ItemID", args.get(0)), args.get(1), price == null ? 0 : Integer.parseInt(price), stacks);
				break;
			}
			case "items":
				items(a);
				break;
			case "widget":
				widget(a);
				break;
			case "close":
				game.closeWidget(id("InterfaceID", a.get(0)));
				break;
			case "world":
			{
				net.runelite.api.WorldType[] types = new net.runelite.api.WorldType[a.size() - 1];
				for (int i = 1; i < a.size(); i++)
				{
					types[i - 1] = net.runelite.api.WorldType.valueOf(a.get(i));
				}
				game.world(num(a, 0), types);
				break;
			}
			case "chat":
				game.chat(a.get(0), a.get(1));
				break;
			case "state":
				game.state(GameState.valueOf(a.get(0)));
				break;
			case "tick":
				for (int i = a.isEmpty() ? 1 : num(a, 0); i > 0; i--)
				{
					tick();
				}
				break;
			case "expect":
				check(new Rule(a, true), "expected ");
				break;
			case "never":
				rules.add(new Rule(a, false));
				break;
			case "always":
				rules.add(new Rule(a, true));
				break;
			case "forget":
				rules.clear();
				break;
			default:
				if (!rig.command(verb, a))
				{
					throw new IllegalArgumentException("Unknown command " + verb);
				}
		}
	}

	private void tick()
	{
		game.tick();
		now += TICK_MS;
		Map<String, String> r = rig.tick(now);
		readings = r == null ? new TreeMap<>() : new TreeMap<>(r);
		for (Rule rule : rules)
		{
			check(rule, rule.want ? "always " : "never ");
		}
	}

	private void walk(int x, int y)
	{
		while (game.at().getX() != x || game.at().getY() != y)
		{
			int nx = game.at().getX() + Integer.signum(x - game.at().getX());
			int ny = game.at().getY() + Integer.signum(y - game.at().getY());
			game.at(nx, ny, game.at().getPlane());
			tick();
		}
	}

	private void spawn(String verb, List<String> a)
	{
		int as = a.indexOf("as");
		List<String> args = as < 0 ? a : a.subList(0, as);
		String handle = as < 0 ? null : a.get(as + 1);
		int id = id("ObjectID", args.get(0));
		int plane = game.at() == null ? 0 : game.at().getPlane();
		if (verb.equals("wall"))
		{
			game.wall(id, num(args, 1), num(args, 2), plane, handle);
		}
		else
		{
			game.object(id, num(args, 1), num(args, 2), plane, args.size() > 3 ? num(args, 3) : 1, args.size() > 4 ? num(args, 4) : 1, handle);
		}
	}

	private void remove(String what)
	{
		if (game.removeNpc(what))
		{
			return;
		}
		game.remove(game.object(what) != null ? what : "id:" + id("ObjectID", what));
	}

	/** Takes "KEY VALUE" out of a command's words, returning the value, or null when it is not there. */
	private static String take(List<String> args, String key)
	{
		int i = args.indexOf(key);
		if (i < 0)
		{
			return null;
		}
		String value = args.get(i + 1);
		args.remove(i + 1);
		args.remove(i);
		return value;
	}

	private void widget(List<String> a)
	{
		FakeGame.Shown w = game.widget(id("InterfaceID", a.get(0)));
		int i = 1;
		while (i < a.size() && a.get(i).equals("child"))
		{
			w = w.child(num(a, i + 1));
			i += 2;
		}
		for (; i < a.size(); i += 2)
		{
			switch (a.get(i))
			{
				case "text":
					w.text = a.get(i + 1);
					break;
				case "hidden":
					w.hidden = Boolean.parseBoolean(a.get(i + 1));
					break;
				case "width":
					w.width = num(a, i + 1);
					break;
				case "height":
					w.height = num(a, i + 1);
					break;
				case "item":
					w.itemId = id("ItemID", a.get(i + 1));
					w.itemQuantity = num(a, i + 2);
					i++;
					break;
				default:
					throw new IllegalArgumentException("A widget has text, hidden, width, height or item, not " + a.get(i));
			}
		}
	}

	private void items(List<String> a)
	{
		int inv = id("InventoryID", a.get(0));
		if (a.get(1).equals("clear"))
		{
			game.emptyContainer(inv);
			return;
		}
		game.fill(inv, id("ItemID", a.get(1)), num(a, 2), a.size() > 3 && a.get(3).equals("stacked"));
	}

	/** One check on the readings: a reading, how to compare, and the value. */
	private static final class Rule
	{
		final String key;
		final String op;
		final String value;
		/** Whether the comparison must hold (expect, always) or must not (never). */
		final boolean want;

		Rule(List<String> a, boolean want)
		{
			if (a.size() != 3)
			{
				throw new IllegalArgumentException("A check is KEY OP VALUE, not " + a);
			}
			this.key = a.get(0);
			this.op = a.get(1);
			this.value = a.get(2);
			this.want = want;
		}

		boolean holds(String actual)
		{
			switch (op)
			{
				case "=":
					return actual.equals(value);
				case "!=":
					return !actual.equals(value);
				case "~":
					return actual.contains(value);
				case "!~":
					return !actual.contains(value);
				default:
					throw new IllegalArgumentException("Unknown comparison " + op);
			}
		}

		@Override
		public String toString()
		{
			return key + " " + op + " \"" + value + "\"";
		}
	}

	private void check(Rule rule, String what)
	{
		String actual = readings.getOrDefault(rule.key, "-");
		if (actual == null)
		{
			actual = "-";
		}
		if (rule.holds(actual) != rule.want)
		{
			StringBuilder b = new StringBuilder(where()).append(what).append(rule)
				.append(", but ").append(rule.key).append(" was \"").append(actual).append("\"\n  readings this tick:");
			readings.forEach((k, v) -> b.append("\n    ").append(k).append(" = ").append(v));
			if (!game.unmodelled().isEmpty())
			{
				b.append("\n  the plugin also read, unmodelled: ").append(game.unmodelled());
			}
			throw new AssertionError(b.toString());
		}
	}

	private String where()
	{
		return (file.equals(name) ? name : name + " via " + file) + ":" + line + " (tick " + game.tickCount() + ", player at " + game.at() + "): ";
	}

	private int plane(List<String> a, int index)
	{
		return a.size() > index ? num(a, index) : game.at() == null ? 0 : game.at().getPlane();
	}

	private static int num(List<String> a, int index)
	{
		return Integer.parseInt(a.get(index));
	}

	/** A game id by its RuneLite name in a gameval class, or as a number. */
	static int id(String gameval, String name)
	{
		if (name.matches("-?\\d+"))
		{
			return Integer.parseInt(name);
		}
		try
		{
			// A nested name, such as InterfaceID's PestStatusOverlay.PEST_STATUS_PORTTXT1, is a nested class's field.
			int dot = name.lastIndexOf('.');
			String owner = "net.runelite.api.gameval." + gameval + (dot < 0 ? "" : "$" + name.substring(0, dot).replace('.', '$'));
			Field f = Class.forName(owner).getField(name.substring(dot + 1));
			// ObjectID keeps half its names in a package-private parent class.
			f.setAccessible(true);
			return f.getInt(null);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalArgumentException("No " + gameval + "." + name, e);
		}
	}

	/** Splits a line into words, keeping quoted text together and dropping a # comment. */
	static List<String> words(String line)
	{
		List<String> out = new ArrayList<>();
		StringBuilder w = null;
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++)
		{
			char c = line.charAt(i);
			if (quoted)
			{
				if (c == '"')
				{
					quoted = false;
					out.add(w.toString());
					w = null;
				}
				else
				{
					w.append(c);
				}
			}
			else if (c == '"')
			{
				quoted = true;
				w = new StringBuilder();
			}
			else if (c == '#')
			{
				break;
			}
			else if (Character.isWhitespace(c))
			{
				if (w != null)
				{
					out.add(w.toString());
					w = null;
				}
			}
			else
			{
				if (w == null)
				{
					w = new StringBuilder();
				}
				w.append(c);
			}
		}
		if (w != null)
		{
			out.add(w.toString());
		}
		return out;
	}
}
