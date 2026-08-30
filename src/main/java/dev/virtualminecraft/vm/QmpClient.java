package dev.virtualminecraft.vm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * Tiny QMP client: one connection per command. Enough for power/reset, {@code quit}, and HMP commands
 * ({@code savevm}/{@code delvm}) through {@code human-monitor-command}.
 */
public final class QmpClient {
	private QmpClient() {
	}

	/** Runs a QMP command with no arguments; returns its {@code return} value (often an empty object). */
	public static JsonObject execute(final QemuLauncher.Endpoints ep, final String command) throws IOException {
		return execute(ep, command, null);
	}

	public static JsonObject execute(final QemuLauncher.Endpoints ep, final String command, final JsonObject arguments) throws IOException {
		final SocketAddress addr = ep.qmpSocket() != null
			? UnixDomainSocketAddress.of(ep.qmpSocket())
			: new InetSocketAddress("127.0.0.1", ep.qmpPort());
		try (SocketChannel ch = addr instanceof UnixDomainSocketAddress ? SocketChannel.open(StandardProtocolFamily.UNIX) : SocketChannel.open()) {
			ch.connect(addr);
			final BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
			final OutputStream out = Channels.newOutputStream(ch);
			readResponse(in); // greeting
			send(out, "{\"execute\":\"qmp_capabilities\"}");
			readResponse(in);
			final JsonObject req = new JsonObject();
			req.addProperty("execute", command);
			if (arguments != null) {
				req.add("arguments", arguments);
			}
			send(out, req.toString());
			return readResponse(in);
		}
	}

	/**
	 * Runs a human-monitor (HMP) command and returns what it printed. HMP reports failures as text rather
	 * than QMP errors, so callers must inspect the output ({@code savevm} prints nothing on success).
	 */
	public static String hmp(final QemuLauncher.Endpoints ep, final String commandLine) throws IOException {
		final JsonObject args = new JsonObject();
		args.addProperty("command-line", commandLine);
		final JsonObject resp = execute(ep, "human-monitor-command", args);
		return resp.has("return") && resp.get("return").isJsonPrimitive() ? resp.get("return").getAsString() : "";
	}

	private static void send(final OutputStream out, final String json) throws IOException {
		out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	private static JsonObject readResponse(final BufferedReader in) throws IOException {
		// QMP responses are newline-delimited JSON objects; events may be interleaved, so skip those.
		for (int i = 0; i < 64; i++) {
			final String line = in.readLine();
			if (line == null) {
				throw new IOException("QMP connection closed");
			}
			final JsonObject obj;
			try {
				obj = JsonParser.parseString(line).getAsJsonObject();
			} catch (final RuntimeException e) {
				throw new IOException("QMP: unparseable line: " + line);
			}
			if (obj.has("event")) {
				continue;
			}
			if (obj.has("error")) {
				throw new IOException("QMP error: " + obj.get("error"));
			}
			return obj;
		}
		throw new IOException("QMP: too many events without a response");
	}
}
