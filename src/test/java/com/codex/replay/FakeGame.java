package com.codex.replay;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/**
 * A stand-in for the game client that a replay drives tick by tick: where the player stands,
 * the ground (heights and walls), skills, varbits, what is carried, and the objects, NPCs and
 * players in the loaded scene. Plugins read it through the real {@link Client} interface, so
 * their own code runs unchanged; only the game behind it is scripted.
 *
 * <p>Anything a plugin asks for that this does not model answers a harmless default (0, false,
 * null) and is listed in {@link #unmodelled()}, so a replay failure can say what the plugin read
 * that the script could not set.
 *
 * <p>Shared by every Codex plugin: keep this file identical across repositories.
 */
public final class FakeGame
{
	/** The loaded scene is this many tiles a side, as in the game. */
	public static final int SCENE = 104;
	/** The world view id of the top-level (non-boat) scene. */
	private static final int TOP = -1;

	/** What the plugin under test is told about; every method is optional. */
	public interface Listener
	{
		default void spawned(TileObject object)
		{
		}

		default void despawned(TileObject object)
		{
		}

		default void varbit(int id, int value)
		{
		}

		default void chat(String type, String message)
		{
		}

		default void gameState(GameState state)
		{
		}

		default void container(int containerId)
		{
		}

		default void npcSpawned(NPC npc)
		{
		}

		default void npcDespawned(NPC npc)
		{
		}

		/** An NPC turned into another (a portal's shield dropping, say); {@code old} is what it was. */
		default void npcChanged(NPC npc, NPCComposition old)
		{
		}

		default void varp(int id, int value)
		{
		}

		/** A skill's level, boost or experience changed. */
		default void stat(Skill skill)
		{
		}
	}

	private Listener listener = new Listener()
	{
	};

	private int baseX;
	private int baseY;
	private int plane;
	private WorldPoint at;
	private GameState state = GameState.LOGGED_IN;
	private int tick;
	private final int[][][] heights = new int[4][SCENE + 1][SCENE + 1];
	private final byte[][][] settings = new byte[4][SCENE][SCENE];
	private final int[][][] flags = new int[4][SCENE][SCENE];
	private final Map<Integer, Integer> varbits = new HashMap<>();
	private final Map<Integer, Integer> varps = new HashMap<>();
	private final Map<Skill, int[]> skills = new EnumMap<>(Skill.class);
	private final Map<Integer, List<Item>> containers = new HashMap<>();
	private final Map<Integer, String> objectNames = new HashMap<>();
	/** What items are called and cost, as the game's item definitions and the price guide say. */
	private final Map<Integer, Object[]> itemDefs = new HashMap<>();
	/** Objects that change with a varbit: the varbit, then the object shown for each of its values. */
	private final Map<Integer, int[]> multilocs = new HashMap<>();
	/** Objects in the scene by the handle given to them in the script. */
	private final Map<String, TileObject> objects = new LinkedHashMap<>();
	private final List<NPC> npcs = new ArrayList<>();
	private final Map<String, NPC> npcNames = new LinkedHashMap<>();
	private final Map<NPC, int[]> npcIds = new HashMap<>();
	private final List<Player> players = new ArrayList<>();
	private final TreeSet<String> unmodelled = new TreeSet<>();
	/** Interface components that are showing, by component id (group << 16 | child). */
	private final Map<Integer, Shown> widgets = new HashMap<>();
	private int world = 301;
	/** The world's types: none for an ordinary members' world. */
	private final java.util.Set<net.runelite.api.WorldType> worldTypes = java.util.EnumSet.noneOf(net.runelite.api.WorldType.class);
	private int nextHandle;

	private final WorldView view = proxy(WorldView.class, this::worldView);
	private final Scene scene = proxy(Scene.class, this::scene);
	private final Player me = proxy(Player.class, (m, a) -> "getCombatLevel".equals(m.getName()) ? combatLevel() : actor(m, a, "You", at, 1));
	private final Client client = proxy(Client.class, this::client);

	public FakeGame()
	{
		for (Skill s : Skill.values())
		{
			skills.put(s, new int[]{1, 1, 0});
		}
		// Every account has Hitpoints 10; several plugins read 0 experience as "not loaded yet".
		skill(Skill.HITPOINTS, 10, 1154);
	}

	public Client client()
	{
		return client;
	}

	public void listen(Listener listener)
	{
		this.listener = listener;
	}

	// ---- the world -------------------------------------------------------------------------

	/** Loads a scene whose south-west tile is this world tile. Clears the ground and all objects. */
	public void scene(int worldX, int worldY, int plane)
	{
		for (TileObject o : new ArrayList<>(objects.values()))
		{
			listener.despawned(o);
		}
		objects.clear();
		for (NPC n : new ArrayList<>(npcs))
		{
			listener.npcDespawned(n);
		}
		npcs.clear();
		npcNames.clear();
		players.clear();
		baseX = worldX;
		baseY = worldY;
		this.plane = plane;
		for (int z = 0; z < 4; z++)
		{
			for (int[] row : heights[z])
			{
				Arrays.fill(row, 0);
			}
			for (int[] row : flags[z])
			{
				Arrays.fill(row, 0);
			}
		}
	}

	/** Puts the player on a world tile, which must be in the loaded scene. */
	public void at(int worldX, int worldY, int plane)
	{
		requireInScene(worldX, worldY);
		this.at = new WorldPoint(worldX, worldY, plane);
		this.plane = plane;
	}

	public WorldPoint at()
	{
		return at;
	}

	/** Sets the ground height (in local units; lower is higher up) of a block of tiles. */
	public void height(int worldX, int worldY, int width, int length, int plane, int height)
	{
		int sx = worldX - baseX;
		int sy = worldY - baseY;
		for (int x = sx; x <= sx + width; x++)
		{
			for (int y = sy; y <= sy + length; y++)
			{
				if (x >= 0 && y >= 0 && x <= SCENE && y <= SCENE)
				{
					heights[plane][x][y] = height;
				}
			}
		}
	}

	/** Makes a block of tiles impassable, as a wall or a rock would. */
	public void block(int worldX, int worldY, int width, int length, int plane)
	{
		setFlags(worldX, worldY, width, length, plane, CollisionDataFlag.BLOCK_MOVEMENT_FULL);
	}

	/** Makes a block of tiles walkable again. */
	public void open(int worldX, int worldY, int width, int length, int plane)
	{
		setFlags(worldX, worldY, width, length, plane, 0);
	}

	private void setFlags(int worldX, int worldY, int width, int length, int plane, int value)
	{
		for (int x = worldX - baseX; x < worldX - baseX + width; x++)
		{
			for (int y = worldY - baseY; y < worldY - baseY + length; y++)
			{
				if (x >= 0 && y >= 0 && x < SCENE && y < SCENE)
				{
					flags[plane][x][y] = value;
				}
			}
		}
	}

	/**
	 * Spawns a game object (a hopper, a ladder, a bank booth) and tells the plugin.
	 *
	 * @param handle the script's name for it, or null for a made-up one starting "#"
	 */
	public String object(int id, int worldX, int worldY, int plane, int width, int length, String handle)
	{
		return spawn(newObject(GameObject.class, id, worldX, worldY, plane, width, length), handle);
	}

	/** Spawns a wall object (an ore vein in the Motherlode Mine is one) and tells the plugin. */
	public String wall(int id, int worldX, int worldY, int plane, String handle)
	{
		return spawn(newObject(WallObject.class, id, worldX, worldY, plane, 1, 1), handle);
	}

	private String spawn(TileObject o, String handle)
	{
		if (handle == null)
		{
			handle = "#" + (++nextHandle);
		}
		if (objects.containsKey(handle))
		{
			throw new IllegalArgumentException("There is already an object called " + handle);
		}
		objects.put(handle, o);
		listener.spawned(o);
		return handle;
	}

	/** Despawns an object by its handle, or every object with an id when given "id:NNN". */
	public void remove(String handle)
	{
		if (handle.startsWith("id:"))
		{
			int id = Integer.parseInt(handle.substring(3));
			for (Map.Entry<String, TileObject> e : new ArrayList<>(objects.entrySet()))
			{
				if (e.getValue().getId() == id)
				{
					objects.remove(e.getKey());
					listener.despawned(e.getValue());
				}
			}
			return;
		}
		TileObject o = objects.remove(handle);
		if (o == null)
		{
			throw new IllegalArgumentException("No object " + handle);
		}
		listener.despawned(o);
	}

	public TileObject object(String handle)
	{
		return objects.get(handle);
	}

	/**
	 * The script's name for an object, else its id, or null when it is not one of the scene's.
	 * This is how a reading names the object a plugin points at.
	 */
	public String nameOf(Object o)
	{
		String handle = handleOf(o);
		return handle == null ? null : handle.startsWith("#") ? String.valueOf(((TileObject) o).getId()) : handle;
	}

	/** The handle of an object in the scene, or null when it is not one of the scene's. */
	public String handleOf(Object o)
	{
		for (Map.Entry<String, TileObject> e : objects.entrySet())
		{
			if (e.getValue() == o)
			{
				return e.getKey();
			}
		}
		return null;
	}

	/** What the game calls an object id, for plugins that find things by name (banks, mostly). */
	public void name(int objectId, String name)
	{
		objectNames.put(objectId, name);
	}

	/**
	 * Spawns an NPC and tells the plugin.
	 *
	 * @param handle the script's name for it, or null
	 */
	/**
	 * Makes an object one that changes with a varbit, as urns and doors do: the game shows
	 * {@code shown[value]} for the varbit's value (the last one past the end), and plugins read it
	 * through {@code ObjectComposition.getImpostor()}.
	 */
	public void multiloc(int objectId, int varbit, int... shown)
	{
		int[] entry = new int[shown.length + 1];
		entry[0] = varbit;
		System.arraycopy(shown, 0, entry, 1, shown.length);
		multilocs.put(objectId, entry);
	}

	public NPC npc(int id, String name, int worldX, int worldY, int plane, int size, String handle)
	{
		return npc(id, name, worldX, worldY, plane, size, 0, handle);
	}

	/** @param level its combat level, which some plugins tell creatures apart by */
	public NPC npc(int id, String name, int worldX, int worldY, int plane, int size, int level, String handle)
	{
		WorldPoint wp = new WorldPoint(worldX, worldY, plane);
		int index = npcs.size();
		int[] now = {id};
		NPC npc = proxy(NPC.class, (m, a) ->
		{
			switch (m.getName())
			{
				case "getId":
					return now[0];
				case "getIndex":
					return index;
				case "getComposition":
				case "getTransformedComposition":
					return npcComposition(now[0], name, size, level);
				case "getCombatLevel":
					return level;
				default:
					return actor(m, a, name, wp, size);
			}
		});
		npcIds.put(npc, now);
		npcs.add(npc);
		if (handle != null)
		{
			npcNames.put(handle, npc);
		}
		listener.npcSpawned(npc);
		return npc;
	}

	/** Turns an NPC into another by the script's name for it, as the game does when a portal's shield drops. */
	public void transform(String handle, int id)
	{
		NPC npc = npcNames.get(handle);
		if (npc == null)
		{
			throw new IllegalArgumentException("No NPC " + handle);
		}
		NPCComposition old = npc.getComposition();
		npcIds.get(npc)[0] = id;
		listener.npcChanged(npc, old);
	}

	private NPCComposition npcComposition(int id, String name, int size, int level)
	{
		return proxy(NPCComposition.class, (m, a) ->
		{
			switch (m.getName())
			{
				case "getName":
					return name;
				case "getSize":
					return size;
				case "getId":
					return id;
				case "getCombatLevel":
					return level;
				default:
					return unknown("NPCComposition." + m.getName(), m);
			}
		});
	}

	/** Despawns an NPC by the script's name for it; false when there is none by that name. */
	public boolean removeNpc(String handle)
	{
		NPC npc = npcNames.remove(handle);
		if (npc == null)
		{
			return false;
		}
		npcs.remove(npc);
		listener.npcDespawned(npc);
		return true;
	}

	/** Another player standing on a tile. */
	public Player player(String name, int worldX, int worldY, int plane)
	{
		WorldPoint wp = new WorldPoint(worldX, worldY, plane);
		Player p = proxy(Player.class, (m, a) -> actor(m, a, name, wp, 1));
		players.add(p);
		return p;
	}

	public void clearActors()
	{
		for (NPC n : new ArrayList<>(npcs))
		{
			listener.npcDespawned(n);
		}
		npcs.clear();
		npcNames.clear();
		players.clear();
	}

	// ---- the account -----------------------------------------------------------------------

	/** Sets a skill's real level (also its boosted level) and experience. */
	public void skill(Skill skill, int level, int xp)
	{
		skills.put(skill, new int[]{level, level, xp});
		listener.stat(skill);
	}

	public void boost(Skill skill, int boosted)
	{
		skills.get(skill)[1] = boosted;
		listener.stat(skill);
	}

	public void xp(Skill skill, int xp)
	{
		skills.get(skill)[2] = xp;
		listener.stat(skill);
	}

	public void varbit(int id, int value)
	{
		Integer old = varbits.put(id, value);
		if (old == null || old != value)
		{
			listener.varbit(id, value);
		}
	}

	public void varp(int id, int value)
	{
		Integer old = varps.put(id, value);
		if (old == null || old != value)
		{
			listener.varp(id, value);
		}
	}

	/** Replaces what an inventory (InventoryID) holds; pairs of item id and quantity, one slot each. */
	public void container(int containerId, int... idQuantity)
	{
		List<Item> items = new ArrayList<>();
		for (int i = 0; i + 1 < idQuantity.length; i += 2)
		{
			items.add(new Item(idQuantity[i], idQuantity[i + 1]));
		}
		containers.put(containerId, items);
		listener.container(containerId);
	}

	/** Puts a number of one item in an inventory, one slot each unless it stacks. */
	public void fill(int containerId, int itemId, int count, boolean stacks)
	{
		List<Item> items = new ArrayList<>(containers.getOrDefault(containerId, Collections.emptyList()));
		if (stacks)
		{
			items.add(new Item(itemId, count));
		}
		else
		{
			for (int i = 0; i < count; i++)
			{
				items.add(new Item(itemId, 1));
			}
		}
		containers.put(containerId, items);
		listener.container(containerId);
	}

	public void emptyContainer(int containerId)
	{
		containers.put(containerId, new ArrayList<>());
		listener.container(containerId);
	}

	/** What a shown interface component holds; children by index. */
	public static final class Shown
	{
		public String text;
		public boolean hidden;
		public int width;
		public int height;
		public int itemId = -1;
		public int itemQuantity;
		final Map<Integer, Shown> children = new HashMap<>();

		public Shown child(int index)
		{
			return children.computeIfAbsent(index, k -> new Shown());
		}
	}

	/** An interface component, shown from now on; change its fields to change what it shows. */
	public Shown widget(int component)
	{
		return widgets.computeIfAbsent(component, k -> new Shown());
	}

	/** Closes an interface component, so the plugin reads it as not there. */
	public void closeWidget(int component)
	{
		widgets.remove(component);
	}

	/** Names an item, with its Grand Exchange price, for plugins that read items by name or price. */
	public void item(int id, String name, int price, boolean stacks)
	{
		itemDefs.put(id, new Object[]{name, price, stacks});
	}

	/** An item's price as the plugin's price lookup gives it: 0 unless the script set one. */
	public int price(int id)
	{
		Object[] d = itemDefs.get(id);
		return d == null ? 0 : (Integer) d[1];
	}

	/** The world the player is on, and its types (such as SEASONAL or PVP); none is an ordinary world. */
	public void world(int world, net.runelite.api.WorldType... types)
	{
		this.world = world;
		worldTypes.clear();
		worldTypes.addAll(Arrays.asList(types));
	}

	public void chat(String type, String message)
	{
		listener.chat(type, message);
	}

	public void state(GameState state)
	{
		this.state = state;
		listener.gameState(state);
	}

	/** Moves the clock on one game tick. */
	public void tick()
	{
		tick++;
	}

	public int tickCount()
	{
		return tick;
	}

	/** The player's combat level, from their real levels, as the game works it out. */
	public int combatLevel()
	{
		return net.runelite.api.Experience.getCombatLevel(level(Skill.ATTACK), level(Skill.STRENGTH), level(Skill.DEFENCE),
			level(Skill.HITPOINTS), level(Skill.MAGIC), level(Skill.RANGED), level(Skill.PRAYER));
	}

	private int level(Skill skill)
	{
		return skills.get(skill)[0];
	}

	/** What a plugin read that this fake does not model, as "Interface.method". */
	public TreeSet<String> unmodelled()
	{
		return unmodelled;
	}

	/** The tile an object stands on, for spawn events: where it is and what else stands there. */
	public net.runelite.api.Tile tile(TileObject o)
	{
		WorldPoint wp = o.getWorldLocation();
		return proxy(net.runelite.api.Tile.class, (m, a) ->
		{
			switch (m.getName())
			{
				case "getWorldLocation":
					return wp;
				case "getLocalLocation":
					return LocalPoint.fromWorld(view, wp.getX(), wp.getY());
				case "getPlane":
					return wp.getPlane();
				case "getRenderLevel":
					return wp.getPlane();
				case "getSceneLocation":
					return new Point(wp.getX() - baseX, wp.getY() - baseY);
				case "getGameObjects":
				{
					GameObject[] out = new GameObject[5];
					int i = 0;
					for (TileObject t : objects.values())
					{
						if (t instanceof GameObject && t.getWorldLocation().equals(wp) && i < out.length)
						{
							out[i++] = (GameObject) t;
						}
					}
					return out;
				}
				case "getWallObject":
					for (TileObject t : objects.values())
					{
						if (t instanceof WallObject && t.getWorldLocation().equals(wp))
						{
							return t;
						}
					}
					return null;
				default:
					return unknown("Tile." + m.getName(), m);
			}
		});
	}

	// ---- the proxies -----------------------------------------------------------------------

	private Object client(Method m, Object[] a)
	{
		switch (m.getName())
		{
			case "getLocalPlayer":
				return at == null ? null : me;
			case "getIntStack":
				// Client scripts (a quest's state, say) leave their answer here: nothing run, all zeroes.
				return new int[1000];
			case "getObjectStack":
				return new Object[1000];
			case "getComponentTable":
				return NO_COMPONENTS;
			case "getTopLevelWorldView":
			case "findWorldViewFromWorldPoint":
				return view;
			case "getWorldView":
				return view;
			case "getScene":
				return scene;
			case "getPlane":
				return plane;
			case "getBaseX":
				return baseX;
			case "getBaseY":
				return baseY;
			case "getGameState":
				return state;
			case "getTickCount":
				return tick;
			case "getVarbitValue":
				return varbits.getOrDefault((Integer) a[0], 0);
			case "getVarpValue":
				return varps.getOrDefault((Integer) a[0], 0);
			case "getRealSkillLevel":
				return skills.get((Skill) a[0])[0];
			case "getBoostedSkillLevel":
				return skills.get((Skill) a[0])[1];
			case "getSkillExperience":
				return skills.get((Skill) a[0])[2];
			case "getItemContainer":
				return container(a[0] instanceof Integer ? (Integer) a[0] : idOf(a[0]));
			case "getObjectDefinition":
				return objectDefinition((Integer) a[0]);
			case "getItemDefinition":
				return itemDefinition((Integer) a[0]);
			case "isInInstancedRegion":
				return false;
			case "getMapRegions":
				return regions();
			case "getCollisionMaps":
				return collisionMaps();
			case "getWidget":
			{
				int id = a.length == 2 ? ((Integer) a[0] << 16) | (Integer) a[1] : (Integer) a[0];
				Shown w = widgets.get(id);
				return w == null ? null : widget(id, w);
			}
			case "getWorld":
				return world;
			case "getWorldType":
				return java.util.EnumSet.copyOf(worldTypes.isEmpty() ? java.util.EnumSet.noneOf(net.runelite.api.WorldType.class) : worldTypes);
			case "getNpcs":
				return new ArrayList<>(npcs);
			case "getPlayers":
				return new ArrayList<>(players);
			default:
				return unknown("Client." + m.getName(), m);
		}
	}

	private Object worldView(Method m, Object[] a)
	{
		switch (m.getName())
		{
			case "getId":
				return TOP;
			case "isTopLevel":
				return true;
			case "isInstance":
				return false;
			case "getScene":
				return scene;
			case "getPlane":
				return plane;
			case "getBaseX":
				return baseX;
			case "getBaseY":
				return baseY;
			case "getSizeX":
			case "getSizeY":
				return SCENE;
			case "getTileHeights":
				return heights;
			case "getTileSettings":
				return settings;
			case "getCollisionMaps":
				return collisionMaps();
			case "getMapRegions":
				return regions();
			case "npcs":
				return set(npcs);
			case "players":
				List<Player> all = new ArrayList<>(players);
				if (at != null)
				{
					all.add(0, me);
				}
				return set(all);
			default:
				return unknown("WorldView." + m.getName(), m);
		}
	}

	private Object scene(Method m, Object[] a)
	{
		switch (m.getName())
		{
			case "getBaseX":
				return baseX;
			case "getBaseY":
				return baseY;
			case "getWorldViewId":
				return TOP;
			case "isInstance":
				return false;
			case "getTiles":
				// A plugin sweeping the scene finds nothing; the replay tells it about objects as they spawn.
				return new net.runelite.api.Tile[4][SCENE][SCENE];
			case "getTileHeights":
				return heights;
			case "getTileSettings":
				return settings;
			default:
				return unknown("Scene." + m.getName(), m);
		}
	}

	private Object actor(Method m, Object[] a, String name, WorldPoint where, int size)
	{
		switch (m.getName())
		{
			case "getName":
				return name;
			case "getWorldLocation":
				return where;
			case "getLocalLocation":
				return where == null ? null : LocalPoint.fromWorld(view, where.getX(), where.getY());
			case "getWorldView":
				return view;
			case "getAnimation":
			case "getPoseAnimation":
				return -1;
			default:
				return unknown("Actor." + m.getName(), m);
		}
	}

	private <T extends TileObject> T newObject(Class<T> type, int id, int worldX, int worldY, int plane, int width, int length)
	{
		requireInScene(worldX, worldY);
		WorldPoint wp = new WorldPoint(worldX, worldY, plane);
		int sx = worldX - baseX;
		int sy = worldY - baseY;
		long hash = ((long) id << 32) ^ ((long) sx << 16) ^ ((long) sy << 2) ^ plane;
		return proxy(type, (m, a) ->
		{
			switch (m.getName())
			{
				case "getId":
					return id;
				case "getPlane":
					return plane;
				case "getWorldLocation":
					return wp;
				case "getLocalLocation":
					// A big object's local point is its centre, as in the game.
					return new LocalPoint((sx << 7) + (width << 6), (sy << 7) + (length << 6), TOP);
				case "getX":
					return (sx << 7) + (width << 6);
				case "getY":
					return (sy << 7) + (length << 6);
				case "getWorldView":
					return view;
				case "getHash":
					return hash;
				case "sizeX":
					return width;
				case "sizeY":
					return length;
				case "getSceneMinLocation":
					return new Point(sx, sy);
				case "getSceneMaxLocation":
					return new Point(sx + width - 1, sy + length - 1);
				default:
					return unknown(type.getSimpleName() + "." + m.getName(), m);
			}
		});
	}

	private net.runelite.api.widgets.Widget widget(int id, Shown w)
	{
		return proxy(net.runelite.api.widgets.Widget.class, (m, a) ->
		{
			switch (m.getName())
			{
				case "getId":
					return id;
				case "getText":
					return w.text;
				case "isHidden":
				case "isSelfHidden":
					return w.hidden;
				case "getWidth":
				case "getOriginalWidth":
					return w.width;
				case "getHeight":
				case "getOriginalHeight":
					return w.height;
				case "getItemId":
					return w.itemId;
				case "getItemQuantity":
					return w.itemQuantity;
				case "getChild":
				{
					Shown c = w.children.get((Integer) a[0]);
					return c == null ? null : widget(id, c);
				}
				case "getChildren":
				case "getDynamicChildren":
				{
					int n = w.children.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
					net.runelite.api.widgets.Widget[] out = new net.runelite.api.widgets.Widget[n];
					w.children.forEach((i, c) -> out[i] = widget(id, c));
					return out;
				}
				default:
					return unknown("Widget." + m.getName(), m);
			}
		});
	}

	private net.runelite.api.ItemComposition itemDefinition(int id)
	{
		Object[] d = itemDefs.get(id);
		return proxy(net.runelite.api.ItemComposition.class, (m, a) ->
		{
			switch (m.getName())
			{
				case "getId":
					return id;
				case "getName":
				case "getMembersName":
					return d == null ? "null" : d[0];
				case "getNote":
				case "getLinkedNoteId":
				case "getPlaceholderId":
				case "getPlaceholderTemplateId":
					return -1;
				case "getPrice":
					return d == null ? 0 : d[1];
				case "isStackable":
					return d != null && (Boolean) d[2];
				case "isTradeable":
				case "isMembers":
					return true;
				default:
					return unknown("ItemComposition." + m.getName(), m);
			}
		});
	}

	private ObjectComposition objectDefinition(int id)
	{
		String name = objectNames.get(id);
		int[] multi = multilocs.get(id);
		return proxy(ObjectComposition.class, (m, a) ->
		{
			switch (m.getName())
			{
				case "getId":
					return id;
				case "getName":
					return name == null ? "null" : name;
				case "getVarbitId":
					return multi == null ? -1 : multi[0];
				case "getVarPlayerId":
					return -1;
				case "getImpostorIds":
					return multi == null ? null : Arrays.copyOfRange(multi, 1, multi.length);
				case "getImpostor":
				{
					if (multi == null)
					{
						return null;
					}
					int value = varbits.getOrDefault(multi[0], 0);
					int shown = multi[1 + Math.max(0, Math.min(value, multi.length - 2))];
					return shown < 0 ? null : objectDefinition(shown);
				}
				case "getActions":
					return new String[5];
				default:
					return unknown("ObjectComposition." + m.getName(), m);
			}
		});
	}

	private ItemContainer container(int id)
	{
		List<Item> items = containers.get(id);
		if (items == null)
		{
			return null;
		}
		Item[] array = items.toArray(new Item[0]);
		return proxy(ItemContainer.class, (m, a) ->
		{
			switch (m.getName())
			{
				case "getId":
					return id;
				case "getItems":
					return array.clone();
				case "size":
					return array.length;
				case "count":
					if (a == null || a.length == 0)
					{
						return (int) Arrays.stream(array).filter(i -> i.getId() > 0).count();
					}
					int n = 0;
					for (Item i : array)
					{
						if (i.getId() == (Integer) a[0])
						{
							n += i.getQuantity();
						}
					}
					return n;
				case "contains":
					return Arrays.stream(array).anyMatch(i -> i.getId() == (Integer) a[0]);
				case "getItem":
					int slot = (Integer) a[0];
					return slot >= 0 && slot < array.length ? array[slot] : null;
				default:
					return unknown("ItemContainer." + m.getName(), m);
			}
		});
	}

	private CollisionData[] collisionMaps()
	{
		CollisionData[] maps = new CollisionData[4];
		for (int z = 0; z < 4; z++)
		{
			int[][] f = flags[z];
			maps[z] = proxy(CollisionData.class, (m, a) -> "getFlags".equals(m.getName()) ? f : unknown("CollisionData." + m.getName(), m));
		}
		return maps;
	}

	private int[] regions()
	{
		TreeSet<Integer> ids = new TreeSet<>();
		for (int x = baseX; x < baseX + SCENE; x += 8)
		{
			for (int y = baseY; y < baseY + SCENE; y += 8)
			{
				ids.add(((x >> 6) << 8) | (y >> 6));
			}
		}
		return ids.stream().mapToInt(Integer::intValue).toArray();
	}

	private void requireInScene(int worldX, int worldY)
	{
		if (worldX < baseX || worldY < baseY || worldX >= baseX + SCENE || worldY >= baseY + SCENE)
		{
			throw new IllegalArgumentException("Tile " + worldX + "," + worldY + " is outside the loaded scene at " + baseX + "," + baseY);
		}
	}

	private static int idOf(Object inventoryId)
	{
		try
		{
			return (Integer) inventoryId.getClass().getMethod("getId").invoke(inventoryId);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalArgumentException(String.valueOf(inventoryId), e);
		}
	}

	private static <T> IndexedObjectSet<T> set(List<T> items)
	{
		return new IndexedObjectSet<T>()
		{
			@Override
			public T byIndex(int index)
			{
				return index >= 0 && index < items.size() ? items.get(index) : null;
			}

			@Override
			public Iterator<T> iterator()
			{
				return items.iterator();
			}
		};
	}

	/** No interface is open beyond the widgets a script shows. */
	private static final net.runelite.api.HashTable<net.runelite.api.WidgetNode> NO_COMPONENTS =
		new net.runelite.api.HashTable<net.runelite.api.WidgetNode>()
		{
			@Override
			public net.runelite.api.WidgetNode get(long value)
			{
				return null;
			}

			@Override
			public Iterator<net.runelite.api.WidgetNode> iterator()
			{
				return java.util.Collections.emptyIterator();
			}
		};

	private Object unknown(String name, Method m)
	{
		unmodelled.add(name);
		return defaultFor(m.getReturnType());
	}

	private static Object defaultFor(Class<?> type)
	{
		if (type == boolean.class)
		{
			return false;
		}
		if (type == int.class)
		{
			return 0;
		}
		if (type == long.class)
		{
			return 0L;
		}
		if (type == double.class)
		{
			return 0d;
		}
		if (type == float.class)
		{
			return 0f;
		}
		if (type == short.class)
		{
			return (short) 0;
		}
		if (type == byte.class)
		{
			return (byte) 0;
		}
		if (type == char.class)
		{
			return (char) 0;
		}
		if (List.class.isAssignableFrom(type) || type == java.util.Collection.class)
		{
			return new ArrayList<>();
		}
		return null;
	}

	/** The behaviour of one interface method call: the method and its arguments. */
	private interface Answer
	{
		Object answer(Method m, Object[] a) throws Throwable;
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, Answer answer)
	{
		InvocationHandler h = (self, m, a) ->
		{
			if (m.getDeclaringClass() == Object.class)
			{
				switch (m.getName())
				{
					case "equals":
						return self == a[0];
					case "hashCode":
						return System.identityHashCode(self);
					default:
						return type.getSimpleName() + "@fake";
				}
			}
			if (m.isDefault())
			{
				// The interface's own default body, which reads the rest of the fake (Java 11 has no invokeDefault).
				return java.lang.invoke.MethodHandles.privateLookupIn(type, java.lang.invoke.MethodHandles.lookup())
					.unreflectSpecial(m, type).bindTo(self).invokeWithArguments(a == null ? new Object[0] : a);
			}
			return answer.answer(m, a);
		};
		return (T) Proxy.newProxyInstance(FakeGame.class.getClassLoader(), new Class<?>[]{type}, h);
	}
}
