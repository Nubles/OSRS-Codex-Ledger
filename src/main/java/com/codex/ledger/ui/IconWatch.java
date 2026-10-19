package com.codex.ledger.ui;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Hands a picture's "loaded" callback over only until the picture has arrived.
 *
 * <p>RuneLite runs a callback given after an item picture has loaded straight away (later, on the
 * client thread). A component that asks for its picture while painting and repaints when told it
 * loaded would then repaint forever. Once a picture is known to have arrived, no more callbacks
 * are passed on for it.
 */
public final class IconWatch
{
	private final Set<Object> arrived = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));

	/**
	 * @param image the picture, compared by identity
	 * @param onLoaded run when it arrives; may be null
	 * @param register passes a callback to the picture, the way AsyncBufferedImage.onLoaded does
	 */
	public void watch(Object image, Runnable onLoaded, Consumer<Runnable> register)
	{
		if (image == null || arrived.contains(image))
		{
			return;
		}
		register.accept(() ->
		{
			arrived.add(image);
			if (onLoaded != null)
			{
				onLoaded.run();
			}
		});
	}

	/** True once the picture has said it arrived. */
	public boolean hasArrived(Object image)
	{
		return image != null && arrived.contains(image);
	}

	/** For tests: how many arrived pictures are remembered. */
	int size()
	{
		synchronized (arrived)
		{
			return arrived.size();
		}
	}
}
