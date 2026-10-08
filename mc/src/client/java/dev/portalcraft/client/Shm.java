package dev.portalcraft.client;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Pagefile-backed named shared memory (Windows), opened through the FFM API. */
public final class Shm {
	public static final String HOST = "Local\\PortalcraftHost";
	public static final String GUEST = "Local\\PortalcraftGuest";
	public static final String FRAME = "Local\\PortalcraftFrame";
	public static final long HOST_SIZE = 65536;
	public static final long GUEST_SIZE = 1L << 21; // header, solid cells, drive state, hole grid (PROTOCOL.md)
	public static final int MAX_W = 2560, MAX_H = 1440;
	public static final long FRAME_SIZE = 4096 + 3L * MAX_W * MAX_H * 12;

	private static final int PAGE_READWRITE = 0x04;
	private static final int FILE_MAP_ALL_ACCESS = 0xF001F;
	private static MethodHandle createFileMapping, mapViewOfFile;

	private Shm() {}

	private static void init() {
		if (createFileMapping != null) return;
		Linker linker = Linker.nativeLinker();
		SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
		createFileMapping = linker.downcallHandle(k32.find("CreateFileMappingW").orElseThrow(),
			FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
		mapViewOfFile = linker.downcallHandle(k32.find("MapViewOfFile").orElseThrow(),
			FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));
	}

	/** Creates or opens the mapping and returns a segment covering all of it. */
	public static MemorySegment open(String name, long size) {
		try {
			init();
			MemorySegment wname = Arena.global().allocateFrom(name, StandardCharsets.UTF_16LE);
			MemorySegment invalid = MemorySegment.ofAddress(-1L);
			MemorySegment h = (MemorySegment) createFileMapping.invokeExact(invalid, MemorySegment.NULL, PAGE_READWRITE,
				(int) (size >>> 32), (int) size, wname);
			if (h.address() == 0) throw new IllegalStateException("CreateFileMappingW failed for " + name);
			MemorySegment view = (MemorySegment) mapViewOfFile.invokeExact(h, FILE_MAP_ALL_ACCESS, 0, 0, size);
			if (view.address() == 0) throw new IllegalStateException("MapViewOfFile failed for " + name);
			return view.reinterpret(size);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}
}
