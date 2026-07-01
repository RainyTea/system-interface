package com.systeminterface.services.portrait;

import com.systeminterface.core.SystemInterfaceConfig;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches and caches NPC portrait images from the OSRS Wiki for the Appraise
 * overlay. The image filename comes from the Infobox Monster wikitext that
 * {@link com.systeminterface.services.drops.LootTables} already parses, so this just
 * resolves it to actual pixels via the wiki's {@code Special:FilePath} redirect.
 *
 * <p>Same opt-in gate as the drop-table fetch: portraits only load when
 * {@code enableWikiLookup} is on (it's the same third-party server, covered by
 * the existing IP-disclosure warning).
 *
 * <p>Threading: {@link #get(String, String)} is called from the overlay render
 * (client thread) and only ever reads the in-memory cache. The disk read /
 * decode runs on the injected executor; the network fetch goes through OkHttp's
 * pool. Images are scaled down to {@link #MAX_DIMENSION}px to keep memory in
 * check (a full-res wiki PNG can be large — {@code w × h × 4} bytes in memory).
 */
@Slf4j
@Singleton
public final class PortraitService
{
	private static final int MAX_DIMENSION = 128;
	private static final String FILEPATH_BASE = "https://oldschool.runescape.wiki/w/Special:FilePath/";
	private static final String USER_AGENT = "system-interface-plugin (RuneLite hub plugin)";

	private static final Path PORTRAIT_DIR =
		RuneLite.RUNELITE_DIR.toPath().resolve("system-interface").resolve("portraits");

	private final OkHttpClient okHttpClient;
	private final SystemInterfaceConfig config;
	private final ScheduledExecutorService executor;

	private final Map<String, BufferedImage> cache = new ConcurrentHashMap<>();
	/** Targets we've already kicked off a load for this session. */
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
	/** Targets the wiki has no usable image for — don't retry this session. */
	private final Set<String> missing = ConcurrentHashMap.newKeySet();

	/** Notified (off-thread) with the target name when a portrait becomes available. */
	private volatile Consumer<String> loadListener;

	@Inject
	public PortraitService(OkHttpClient okHttpClient, SystemInterfaceConfig config,
		ScheduledExecutorService executor)
	{
		this.okHttpClient = okHttpClient;
		this.config = config;
		this.executor = executor;
	}

	/** Register a single listener fired when any portrait finishes loading (off the client thread). */
	public void setLoadListener(Consumer<String> listener)
	{
		this.loadListener = listener;
	}

	private void fireLoaded(String target)
	{
		final Consumer<String> l = loadListener;
		if (l != null)
		{
			l.accept(target);
		}
	}

	/**
	 * @return the cached portrait for {@code target}, or {@code null} if not yet
	 *         available. On a miss, kicks off an async disk-load/fetch (when wiki
	 *         lookup is enabled and an {@code imageFile} is known) so the image
	 *         appears on a later frame.
	 */
	public BufferedImage get(String target, String imageFile)
	{
		final BufferedImage cached = cache.get(target);
		if (cached != null)
		{
			return cached;
		}
		if (!config.enableWikiLookup() || imageFile == null || imageFile.isEmpty()
			|| missing.contains(target) || !inFlight.add(target))
		{
			return null;
		}
		executor.execute(() -> loadOrFetch(target, imageFile));
		return null;
	}

	private void loadOrFetch(String target, String imageFile)
	{
		// Disk cache first.
		final Path file = PORTRAIT_DIR.resolve(sanitize(target) + ".png");
		try
		{
			if (Files.exists(file))
			{
				final BufferedImage raw = ImageIO.read(file.toFile());
				if (raw != null)
				{
					cache.put(target, scale(raw));
					inFlight.remove(target);
					fireLoaded(target);
					return;
				}
			}
		}
		catch (IOException e)
		{
			log.debug("Failed to read cached portrait for '{}'", target, e);
		}
		fetch(target, imageFile);
	}

	private void fetch(String target, String imageFile)
	{
		final HttpUrl url = HttpUrl.parse(FILEPATH_BASE + encode(imageFile));
		if (url == null)
		{
			missing.add(target);
			inFlight.remove(target);
			return;
		}
		final Request request = new Request.Builder()
			.url(url)
			.header("User-Agent", USER_AGENT)
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Portrait fetch failed for '{}'", target, e);
				inFlight.remove(target); // transient — allow retry next session
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					if (!response.isSuccessful() || body == null)
					{
						missing.add(target);
						return;
					}
					final BufferedImage raw = ImageIO.read(new ByteArrayInputStream(body.bytes()));
					if (raw == null)
					{
						missing.add(target);
						return;
					}
					final BufferedImage scaled = scale(raw);
					cache.put(target, scaled);
					save(target, scaled);
					fireLoaded(target);
					log.debug("Portrait cached for '{}'", target);
				}
				catch (IOException e)
				{
					log.debug("Portrait decode failed for '{}'", target, e);
					missing.add(target);
				}
				finally
				{
					inFlight.remove(target);
				}
			}
		});
	}

	private void save(String target, BufferedImage image)
	{
		try
		{
			Files.createDirectories(PORTRAIT_DIR);
			ImageIO.write(image, "png", PORTRAIT_DIR.resolve(sanitize(target) + ".png").toFile());
		}
		catch (IOException e)
		{
			log.debug("Failed to write portrait cache for '{}'", target, e);
		}
	}

	/** Scales {@code src} down so its largest side is at most {@link #MAX_DIMENSION}, preserving aspect. */
	private static BufferedImage scale(BufferedImage src)
	{
		final int w = src.getWidth();
		final int h = src.getHeight();
		final int max = Math.max(w, h);
		if (max <= MAX_DIMENSION)
		{
			return src;
		}
		final double factor = (double) MAX_DIMENSION / max;
		final int nw = Math.max(1, (int) Math.round(w * factor));
		final int nh = Math.max(1, (int) Math.round(h * factor));
		final BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
		final java.awt.Graphics2D g = dst.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
			java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(src, 0, 0, nw, nh, null);
		g.dispose();
		return dst;
	}

	private static String encode(String filename)
	{
		try
		{
			// Special:FilePath accepts a percent-encoded filename; spaces as %20.
			return URLEncoder.encode(filename, StandardCharsets.UTF_8.name()).replace("+", "%20");
		}
		catch (Exception e)
		{
			return filename;
		}
	}

	private static String sanitize(String name)
	{
		return name.replaceAll("[^A-Za-z0-9_-]", "_");
	}
}
