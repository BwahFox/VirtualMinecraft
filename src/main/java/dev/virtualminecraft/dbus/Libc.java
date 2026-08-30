package dev.virtualminecraft.dbus;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

/**
 * The handful of libc calls Java has no API for: unix sockets that can carry file descriptors ({@code sendmsg} /
 * {@code recvmsg} with {@code SCM_RIGHTS}) and {@code mmap}. Java 25 FFM, no native library of our own. Linux
 * x86-64 / aarch64 struct layouts (glibc and musl agree on these). Used only by the QEMU D-Bus display link;
 * everything else in the mod stays on {@code java.nio}.
 * <p>
 * The JVM prints a one-time "restricted method" warning for the first FFM call unless it was started with
 * {@code --enable-native-access=ALL-UNNAMED}; the dev run configs pass it, a launcher can, and without it the
 * warning is harmless.
 */
public final class Libc {
	public static final int AF_UNIX = 1;
	public static final int SOCK_STREAM = 1;
	public static final int SOL_SOCKET = 1;
	public static final int SCM_RIGHTS = 1;
	public static final int PROT_READ = 1;
	public static final int MAP_SHARED = 1;
	private static final int UNIX_PATH_MAX = 108;
	private static final int EINTR = 4;

	private static final Linker LINKER = Linker.nativeLinker();
	private static final SymbolLookup LIB = LINKER.defaultLookup();
	private static final Linker.Option ERRNO = Linker.Option.captureCallState("errno");
	private static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
	private static final VarHandle ERRNO_HANDLE = CAPTURE.varHandle(MemoryLayout.PathElement.groupElement("errno"));

	private static final MethodHandle SOCKET = down("socket", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
	private static final MethodHandle CONNECT = down("connect", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
	private static final MethodHandle SOCKETPAIR = down("socketpair", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
	private static final MethodHandle SENDMSG = down("sendmsg", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
	private static final MethodHandle RECVMSG = down("recvmsg", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
	private static final MethodHandle READ = down("read", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
	private static final MethodHandle WRITE = down("write", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
	private static final MethodHandle CLOSE = down("close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
	private static final MethodHandle MMAP = down("mmap", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
	private static final MethodHandle MUNMAP = down("munmap", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

	// struct msghdr { void* name; socklen_t namelen; [pad]; iovec* iov; size_t iovlen; void* control; size_t controllen; int flags; [pad] } = 56
	private static final long MSG_NAME = 0;
	private static final long MSG_NAMELEN = 8;
	private static final long MSG_IOV = 16;
	private static final long MSG_IOVLEN = 24;
	private static final long MSG_CONTROL = 32;
	private static final long MSG_CONTROLLEN = 40;
	private static final long MSG_FLAGS = 48;
	private static final long MSGHDR_SIZE = 56;
	// struct iovec { void* base; size_t len; } = 16
	private static final long IOVEC_SIZE = 16;
	// struct cmsghdr { size_t len; int level; int type; } = 16, data follows (CMSG_ALIGN is 8 on 64-bit)
	private static final long CMSG_HDR = 16;

	private Libc() {
	}

	private static MethodHandle down(final String name, final FunctionDescriptor fd) {
		return LINKER.downcallHandle(LIB.find(name).orElseThrow(() -> new UnsatisfiedLinkError("libc has no " + name)), fd, ERRNO);
	}

	/** Whether FFM and libc are usable here at all (Linux/macOS JVMs; never on Windows, where none of this is needed). */
	public static boolean available() {
		try {
			return System.getProperty("os.name", "").toLowerCase().contains("linux") && LIB.find("sendmsg").isPresent();
		} catch (final Throwable t) {
			return false;
		}
	}

	private static int errno(final MemorySegment capture) {
		return (int) ERRNO_HANDLE.get(capture, 0L);
	}

	private static IOException fail(final String what, final MemorySegment capture) {
		return new IOException(what + " failed: errno " + errno(capture));
	}

	/** Connects a blocking {@code AF_UNIX} stream socket to a path; returns the fd. */
	public static int connectUnix(final String path) throws IOException {
		final byte[] p = path.getBytes(StandardCharsets.UTF_8);
		if (p.length >= UNIX_PATH_MAX) {
			throw new IOException("unix socket path too long: " + path);
		}
		try (Arena arena = Arena.ofConfined()) {
			final MemorySegment capture = arena.allocate(CAPTURE);
			final int fd = (int) SOCKET.invokeExact(capture, AF_UNIX, SOCK_STREAM, 0);
			if (fd < 0) {
				throw fail("socket", capture);
			}
			// struct sockaddr_un { sa_family_t family (u16); char path[108]; }
			final MemorySegment addr = arena.allocate(2 + UNIX_PATH_MAX, 8);
			addr.set(ValueLayout.JAVA_SHORT, 0, (short) AF_UNIX);
			MemorySegment.copy(MemorySegment.ofArray(p), 0, addr, 2, p.length);
			final int rc = (int) CONNECT.invokeExact(capture, fd, addr, 2 + p.length + 1);
			if (rc != 0) {
				final int e = errno(capture);
				close(fd);
				throw new IOException("connect(" + path + ") failed: errno " + e);
			}
			return fd;
		} catch (final IOException e) {
			throw e;
		} catch (final Throwable t) {
			throw new IOException(t);
		}
	}

	/** A connected pair of {@code AF_UNIX} stream sockets. */
	public static int[] socketpair() throws IOException {
		try (Arena arena = Arena.ofConfined()) {
			final MemorySegment capture = arena.allocate(CAPTURE);
			final MemorySegment fds = arena.allocate(ValueLayout.JAVA_INT, 2);
			final int rc = (int) SOCKETPAIR.invokeExact(capture, AF_UNIX, SOCK_STREAM, 0, fds);
			if (rc != 0) {
				throw fail("socketpair", capture);
			}
			return new int[] { fds.getAtIndex(ValueLayout.JAVA_INT, 0), fds.getAtIndex(ValueLayout.JAVA_INT, 1) };
		} catch (final IOException e) {
			throw e;
		} catch (final Throwable t) {
			throw new IOException(t);
		}
	}

	/** Sends bytes, optionally with file descriptors attached ({@code SCM_RIGHTS}). Loops until everything is written. */
	public static void sendAll(final int fd, final byte[] data, final int[] fdsToPass) throws IOException {
		try (Arena arena = Arena.ofConfined()) {
			final MemorySegment capture = arena.allocate(CAPTURE);
			final MemorySegment buf = arena.allocate(data.length);
			MemorySegment.copy(MemorySegment.ofArray(data), 0, buf, 0, data.length);
			final MemorySegment iov = arena.allocate(IOVEC_SIZE, 8);
			final MemorySegment msg = arena.allocate(MSGHDR_SIZE, 8);
			int off = 0;
			boolean first = true;
			while (off < data.length) {
				iov.set(ValueLayout.ADDRESS, 0, buf.asSlice(off));
				iov.set(ValueLayout.JAVA_LONG, 8, data.length - off);
				msg.fill((byte) 0);
				msg.set(ValueLayout.ADDRESS, MSG_IOV, iov);
				msg.set(ValueLayout.JAVA_LONG, MSG_IOVLEN, 1);
				if (first && fdsToPass != null && fdsToPass.length > 0) {
					final long dataLen = 4L * fdsToPass.length;
					final long space = CMSG_HDR + ((dataLen + 7) & ~7L);
					final MemorySegment cmsg = arena.allocate(space, 8);
					cmsg.fill((byte) 0);
					cmsg.set(ValueLayout.JAVA_LONG, 0, CMSG_HDR + dataLen);
					cmsg.set(ValueLayout.JAVA_INT, 8, SOL_SOCKET);
					cmsg.set(ValueLayout.JAVA_INT, 12, SCM_RIGHTS);
					for (int i = 0; i < fdsToPass.length; i++) {
						cmsg.set(ValueLayout.JAVA_INT, CMSG_HDR + 4L * i, fdsToPass[i]);
					}
					msg.set(ValueLayout.ADDRESS, MSG_CONTROL, cmsg);
					msg.set(ValueLayout.JAVA_LONG, MSG_CONTROLLEN, space);
				}
				final long n = (long) SENDMSG.invokeExact(capture, fd, msg, 0);
				if (n < 0) {
					if (errno(capture) == EINTR) {
						continue;
					}
					throw fail("sendmsg", capture);
				}
				off += (int) n;
				first = false;
			}
		} catch (final IOException e) {
			throw e;
		} catch (final Throwable t) {
			throw new IOException(t);
		}
	}

	/** One {@code recvmsg}: bytes read into {@code into} (or -1 at EOF) and any file descriptors that arrived with them. */
	public record Received(int bytes, int[] fds) {
	}

	public static Received receive(final int fd, final byte[] into, final int maxFds) throws IOException {
		try (Arena arena = Arena.ofConfined()) {
			final MemorySegment capture = arena.allocate(CAPTURE);
			final MemorySegment buf = arena.allocate(into.length);
			final MemorySegment iov = arena.allocate(IOVEC_SIZE, 8);
			iov.set(ValueLayout.ADDRESS, 0, buf);
			iov.set(ValueLayout.JAVA_LONG, 8, into.length);
			final long space = CMSG_HDR + ((4L * Math.max(1, maxFds) + 7) & ~7L);
			final MemorySegment cmsg = arena.allocate(space, 8);
			final MemorySegment msg = arena.allocate(MSGHDR_SIZE, 8);
			while (true) {
				msg.fill((byte) 0);
				cmsg.fill((byte) 0);
				msg.set(ValueLayout.ADDRESS, MSG_IOV, iov);
				msg.set(ValueLayout.JAVA_LONG, MSG_IOVLEN, 1);
				msg.set(ValueLayout.ADDRESS, MSG_CONTROL, cmsg);
				msg.set(ValueLayout.JAVA_LONG, MSG_CONTROLLEN, space);
				final long n = (long) RECVMSG.invokeExact(capture, fd, msg, 0);
				if (n < 0) {
					if (errno(capture) == EINTR) {
						continue;
					}
					throw fail("recvmsg", capture);
				}
				if (n == 0) {
					return new Received(-1, new int[0]);
				}
				MemorySegment.copy(buf, 0, MemorySegment.ofArray(into), 0, n);
				int[] fds = new int[0];
				final long controllen = msg.get(ValueLayout.JAVA_LONG, MSG_CONTROLLEN);
				if (controllen >= CMSG_HDR) {
					final long len = cmsg.get(ValueLayout.JAVA_LONG, 0);
					final int level = cmsg.get(ValueLayout.JAVA_INT, 8);
					final int type = cmsg.get(ValueLayout.JAVA_INT, 12);
					if (level == SOL_SOCKET && type == SCM_RIGHTS && len > CMSG_HDR) {
						final int count = (int) ((len - CMSG_HDR) / 4);
						fds = new int[count];
						for (int i = 0; i < count; i++) {
							fds[i] = cmsg.get(ValueLayout.JAVA_INT, CMSG_HDR + 4L * i);
						}
					}
				}
				return new Received((int) n, fds);
			}
		} catch (final IOException e) {
			throw e;
		} catch (final Throwable t) {
			throw new IOException(t);
		}
	}

	/** Plain blocking {@code write} of the whole array. */
	public static void writeAll(final int fd, final byte[] data) throws IOException {
		sendAll(fd, data, null);
	}

	/** Plain blocking {@code read}; -1 at EOF. */
	public static int read(final int fd, final byte[] into) throws IOException {
		return receive(fd, into, 0).bytes();
	}

	public static void close(final int fd) {
		try (Arena arena = Arena.ofConfined()) {
			final MemorySegment capture = arena.allocate(CAPTURE);
			final int rc = (int) CLOSE.invokeExact(capture, fd);
			if (rc != 0 && errno(capture) != 0) {
				// nothing useful to do; the fd is gone either way
			}
		} catch (final Throwable ignored) {
		}
	}

	/** Maps {@code length} bytes of {@code fd} read-only and shared; the segment lives until {@link #munmap}. */
	public static MemorySegment mmapReadOnly(final int fd, final long offset, final long length) throws IOException {
		try (Arena arena = Arena.ofConfined()) {
			final MemorySegment capture = arena.allocate(CAPTURE);
			final MemorySegment p = (MemorySegment) MMAP.invokeExact(capture, MemorySegment.NULL, length, PROT_READ, MAP_SHARED, fd, offset);
			if (p.address() == -1L || p.address() == 0xFFFFFFFFFFFFFFFFL) {
				throw fail("mmap", capture);
			}
			return p.reinterpret(length);
		} catch (final IOException e) {
			throw e;
		} catch (final Throwable t) {
			throw new IOException(t);
		}
	}

	public static void munmap(final MemorySegment mapped) {
		try (Arena arena = Arena.ofConfined()) {
			final MemorySegment capture = arena.allocate(CAPTURE);
			final int rc = (int) MUNMAP.invokeExact(capture, mapped, mapped.byteSize());
			if (rc != 0) {
				// ignore; a leaked mapping is the least of our problems if this fails
			}
		} catch (final Throwable ignored) {
		}
	}
}
