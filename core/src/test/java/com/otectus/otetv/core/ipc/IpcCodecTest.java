package com.otectus.otetv.core.ipc;

import com.otectus.otetv.core.net.LanGrant;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IpcCodecTest {
    @Test
    void roundTripsFramesInOrder() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        IpcWriter writer = new IpcWriter(bytes);
        writer.send(IpcType.PING, new PayloadWriter().i64(42).i64(7));
        writer.send(IpcType.SEEK, new PayloadWriter().i32(3).i64(90_000).bool(true));
        IpcReader reader = new IpcReader(new ByteArrayInputStream(bytes.toByteArray()), IpcType.Direction.TO_WORKER);
        IpcReader.Header h = reader.readHeader();
        assertEquals(IpcType.PING, h.type());
        PayloadReader p = new PayloadReader(reader.readPayload(h));
        assertEquals(42, p.i64());
        assertEquals(7, p.i64());
        h = reader.readHeader();
        assertEquals(IpcType.SEEK, h.type());
        p = new PayloadReader(reader.readPayload(h));
        assertEquals(3, p.i32());
        assertEquals(90_000, p.i64());
        assertEquals(true, p.bool());
        assertNull(reader.readHeader());
    }

    @Test
    void rejectsUnknownTypesWrongDirectionAndOversizedFrames() {
        byte[] unknown = {0, 0, 0, 1, (byte) 0x7F};
        assertThrows(IpcProtocolException.class, () -> new IpcReader(new ByteArrayInputStream(unknown), IpcType.Direction.TO_WORKER).readHeader());
        byte[] wrongDirection = new PayloadWriter().toFrame(IpcType.EOS);
        assertThrows(IpcProtocolException.class, () -> new IpcReader(new ByteArrayInputStream(wrongDirection), IpcType.Direction.TO_WORKER).readHeader());
        byte[] huge = {0x10, 0, 0, 0, (byte) IpcType.PING.code()};
        assertThrows(IpcProtocolException.class, () -> new IpcReader(new ByteArrayInputStream(huge), IpcType.Direction.TO_WORKER).readHeader());
        byte[] zero = {0, 0, 0, 0, 1};
        assertThrows(IpcProtocolException.class, () -> new IpcReader(new ByteArrayInputStream(zero), IpcType.Direction.TO_WORKER).readHeader());
    }

    @Test
    void payloadReaderRejectsTruncationNonFiniteEnumsAndBadUtf8() {
        assertThrows(IpcProtocolException.class, () -> new PayloadReader(new byte[3]).i32());
        byte[] nan = new PayloadWriter().f64(Double.NaN).toByteArray();
        assertThrows(IpcProtocolException.class, () -> new PayloadReader(nan).f64());
        byte[] inf = new PayloadWriter().f64(Double.POSITIVE_INFINITY).toByteArray();
        assertThrows(IpcProtocolException.class, () -> new PayloadReader(inf).f64());
        assertThrows(IpcProtocolException.class, () -> new PayloadReader(new byte[]{9}).enumValue(PixelFormat.values()));
        assertThrows(IpcProtocolException.class, () -> new PayloadReader(new byte[]{0, 2, (byte) 0xC3, (byte) 0x28}).str(16));
        byte[] longString = new PayloadWriter().str("x".repeat(100)).toByteArray();
        assertThrows(IpcProtocolException.class, () -> new PayloadReader(longString).str(10));
        assertThrows(IpcProtocolException.class, () -> new PayloadReader(new byte[]{2}).bool());
    }

    @Test
    void frameHeaderValidationCatchesInconsistentGeometry() throws Exception {
        FrameHeader ok = new FrameHeader(1, 1, 0, 640, 360, 640 * 4, PixelFormat.RGBA);
        ok.validate(FrameHeader.BYTES + 640 * 360 * 4, true);
        assertThrows(IpcProtocolException.class, () -> ok.validate(FrameHeader.BYTES + 640 * 360 * 4 - 1, true));
        FrameHeader badStride = new FrameHeader(1, 1, 0, 640, 360, 100, PixelFormat.RGBA);
        assertThrows(IpcProtocolException.class, () -> badStride.validate(FrameHeader.BYTES + 100 * 360, true));
        FrameHeader tooBig = new FrameHeader(1, 1, 0, 3840, 2160, 3840 * 4, PixelFormat.BGRA);
        assertThrows(IpcProtocolException.class, () -> tooBig.validate(FrameHeader.BYTES + 3840 * 2160 * 4, true));
        tooBig.validate(FrameHeader.BYTES + 3840 * 2160 * 4, false);
    }

    @Test
    void frameHeaderReadRejectsOutOfRangeDimensions() {
        PayloadWriter w = new PayloadWriter();
        w.i32(1).i32(1).i64(0).i32(100_000).i32(10).i32(400_000).u8(0);
        assertThrows(IpcProtocolException.class, () -> FrameHeader.read(new PayloadReader(w.toByteArray())));
    }

    @Test
    void videoBodiesStreamWithoutCopy() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        IpcWriter writer = new IpcWriter(bytes);
        FrameHeader header = new FrameHeader(2, 9, 33_000_000L, 2, 2, 8, PixelFormat.RGBA);
        PayloadWriter hw = new PayloadWriter();
        header.write(hw);
        writer.sendWithBody(IpcType.VIDEO_FRAME, hw, ByteBuffer.allocateDirect(16));
        IpcReader reader = new IpcReader(new ByteArrayInputStream(bytes.toByteArray()), IpcType.Direction.TO_CLIENT);
        IpcReader.Header h = reader.readHeader();
        assertEquals(IpcType.VIDEO_FRAME, h.type());
        byte[] hdr = new byte[FrameHeader.BYTES];
        reader.readFully(hdr, 0, hdr.length);
        FrameHeader parsed = FrameHeader.read(new PayloadReader(hdr));
        parsed.validate(h.payloadLength(), true);
        assertEquals(9, parsed.sequence());
    }

    @Test
    void sourceGrantRoundTripsAndValidates() throws Exception {
        SourceGrant grant = new SourceGrant(4, SourceGrant.Kind.HTTPS, "https://media.example.com/a.webm", null,
                List.of(new LanGrant("192.168.1.2", 8096)), 960, 540, 30, 12_000, 0, true, true, false);
        PayloadWriter w = new PayloadWriter();
        grant.write(w);
        SourceGrant back = SourceGrant.read(new PayloadReader(w.toByteArray()));
        assertEquals(grant, back);
        PayloadWriter bad = new PayloadWriter();
        new SourceGrant(4, SourceGrant.Kind.FILE, "/x", null, List.of(), 960, 540, 30, 0, 0, false, true, false).write(bad);
        byte[] tampered = bad.toByteArray();
        tampered[tampered.length - 2] = 99; // audio track beyond range... corrupts trailing fields
        assertThrows(IpcProtocolException.class, () -> SourceGrant.read(new PayloadReader(tampered)));
    }
}
