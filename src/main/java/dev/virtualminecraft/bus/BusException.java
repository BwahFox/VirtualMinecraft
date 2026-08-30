package dev.virtualminecraft.bus;

/** A JSON-RPC error to send back to the guest. Codes follow the JSON-RPC 2.0 spec where one exists. */
public final class BusException extends Exception {
	public static final int PARSE_ERROR = -32700;
	public static final int INVALID_REQUEST = -32600;
	public static final int METHOD_NOT_FOUND = -32601;
	public static final int INVALID_PARAMS = -32602;
	public static final int COMPONENT_ERROR = -32000;
	public static final int RATE_LIMITED = -32001;
	public static final int NOT_LOADED = -32002;
	public static final int NO_SUCH_COMPONENT = -32003;

	public final int code;

	public BusException(final int code, final String message) {
		super(message);
		this.code = code;
	}

	public static BusException invalidParams(final String message) {
		return new BusException(INVALID_PARAMS, message);
	}
}
