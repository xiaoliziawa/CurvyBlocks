package com.lirxowo.curvyblocks.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.lirxowo.curvyblocks.CurvyBlocks;
import com.lirxowo.curvyblocks.geometry.CrossSection;
import com.lirxowo.curvyblocks.geometry.CurveBend;
import com.lirxowo.curvyblocks.geometry.CurveLimits;
import com.lirxowo.curvyblocks.geometry.CurvePoint;
import com.lirxowo.curvyblocks.placement.PlacementResult;
import com.lirxowo.curvyblocks.world.Curve;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

public final class CurvePayloads {
    public static final int MAX_BATCH_SIZE = 32;

    private CurvePayloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> createType(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CurvyBlocks.MODID, name));
    }

    public interface Clientbound extends CustomPacketPayload {
    }

    public record Place(int requestId, int thickness, CrossSection section, List<CurvePoint> points,
                        List<CurveBend> bends) implements CustomPacketPayload {
        public static final Type<Place> TYPE = createType("place");
        public static final StreamCodec<FriendlyByteBuf, Place> CODEC = StreamCodec.of((buffer, value) -> {
            buffer.writeVarInt(value.requestId);
            buffer.writeByte(value.thickness);
            buffer.writeEnum(value.section);
            writePoints(buffer, value.points);
            writeBends(buffer, value.bends);
        }, buffer -> {
            int requestId = buffer.readVarInt();
            int thickness = buffer.readUnsignedByte();
            CrossSection section = buffer.readEnum(CrossSection.class);
            List<CurvePoint> points = readPoints(buffer);
            return new Place(requestId, thickness, section, points, readBends(buffer, points.size() - 1));
        });

        public Place(int requestId, int thickness, CrossSection section, List<CurvePoint> points) {
            this(requestId, thickness, section, points, List.of());
        }

        @Override
        public Type<Place> type() {
            return TYPE;
        }
    }

    public record Remove(int requestId, long curveId) implements CustomPacketPayload {
        public static final Type<Remove> TYPE = createType("remove");
        public static final StreamCodec<FriendlyByteBuf, Remove> CODEC = StreamCodec.of((buffer, value) -> {
            buffer.writeVarInt(value.requestId);
            buffer.writeVarLong(value.curveId);
        }, buffer -> new Remove(buffer.readVarInt(), buffer.readVarLong()));

        @Override
        public Type<Remove> type() {
            return TYPE;
        }
    }

    public record ChunkSync(ResourceLocation dimension, long chunk, boolean replace, List<Curve> curves) implements Clientbound {
        public static final Type<ChunkSync> TYPE = createType("chunk_sync");
        public static final StreamCodec<FriendlyByteBuf, ChunkSync> CODEC = StreamCodec.of((buffer, value) -> {
            buffer.writeResourceLocation(value.dimension);
            buffer.writeLong(value.chunk);
            buffer.writeBoolean(value.replace);
            buffer.writeVarInt(value.curves.size());
            for (Curve curve : value.curves) {
                writeCurve(buffer, curve);
            }
        }, buffer -> {
            ResourceLocation dimension = buffer.readResourceLocation();
            long chunk = buffer.readLong();
            boolean replace = buffer.readBoolean();
            int count = buffer.readVarInt();
            if (count < 0 || count > MAX_BATCH_SIZE) {
                throw new DecoderException("Invalid curve batch size");
            }
            List<Curve> curves = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                curves.add(readCurve(buffer));
            }
            return new ChunkSync(dimension, chunk, replace, List.copyOf(curves));
        });

        @Override
        public Type<ChunkSync> type() {
            return TYPE;
        }
    }

    public record ForgetChunk(ResourceLocation dimension, long chunk) implements Clientbound {
        public static final Type<ForgetChunk> TYPE = createType("forget_chunk");
        public static final StreamCodec<FriendlyByteBuf, ForgetChunk> CODEC = StreamCodec.of((buffer, value) -> {
            buffer.writeResourceLocation(value.dimension);
            buffer.writeLong(value.chunk);
        }, buffer -> new ForgetChunk(buffer.readResourceLocation(), buffer.readLong()));

        @Override
        public Type<ForgetChunk> type() {
            return TYPE;
        }
    }

    public record Added(ResourceLocation dimension, Curve curve) implements Clientbound {
        public static final Type<Added> TYPE = createType("added");
        public static final StreamCodec<FriendlyByteBuf, Added> CODEC = StreamCodec.of((buffer, value) -> {
            buffer.writeResourceLocation(value.dimension);
            writeCurve(buffer, value.curve);
        }, buffer -> new Added(buffer.readResourceLocation(), readCurve(buffer)));

        @Override
        public Type<Added> type() {
            return TYPE;
        }
    }

    public record Removed(ResourceLocation dimension, long curveId) implements Clientbound {
        public static final Type<Removed> TYPE = createType("removed");
        public static final StreamCodec<FriendlyByteBuf, Removed> CODEC = StreamCodec.of((buffer, value) -> {
            buffer.writeResourceLocation(value.dimension);
            buffer.writeVarLong(value.curveId);
        }, buffer -> new Removed(buffer.readResourceLocation(), buffer.readVarLong()));

        @Override
        public Type<Removed> type() {
            return TYPE;
        }
    }

    public record Response(int requestId, PlacementResult result, int amount) implements Clientbound {
        public static final Type<Response> TYPE = createType("response");
        public static final StreamCodec<FriendlyByteBuf, Response> CODEC = StreamCodec.of((buffer, value) -> {
            buffer.writeVarInt(value.requestId);
            buffer.writeEnum(value.result);
            buffer.writeVarInt(value.amount);
        }, buffer -> new Response(buffer.readVarInt(), buffer.readEnum(PlacementResult.class), buffer.readVarInt()));

        @Override
        public Type<Response> type() {
            return TYPE;
        }
    }

    public record Mode(boolean enabled) implements CustomPacketPayload {
        public static final Type<Mode> TYPE = createType("mode");
        public static final StreamCodec<FriendlyByteBuf, Mode> CODEC = StreamCodec.of(
                (buffer, value) -> buffer.writeBoolean(value.enabled), buffer -> new Mode(buffer.readBoolean()));

        @Override
        public Type<Mode> type() {
            return TYPE;
        }
    }

    public record UseCapture(boolean captured) implements CustomPacketPayload {
        public static final Type<UseCapture> TYPE = createType("use_capture");
        public static final StreamCodec<FriendlyByteBuf, UseCapture> CODEC = StreamCodec.of(
                (buffer, value) -> buffer.writeBoolean(value.captured), buffer -> new UseCapture(buffer.readBoolean()));

        @Override
        public Type<UseCapture> type() {
            return TYPE;
        }
    }

    private static void writeCurve(FriendlyByteBuf buffer, Curve curve) {
        buffer.writeVarLong(curve.id());
        buffer.writeVarInt(Block.getId(curve.material()));
        buffer.writeByte(curve.thickness());
        buffer.writeEnum(curve.section());
        writePoints(buffer, curve.points());
        writeBends(buffer, curve.bends());
    }

    private static Curve readCurve(FriendlyByteBuf buffer) {
        long id = buffer.readVarLong();
        int state = buffer.readVarInt();
        int thickness = buffer.readUnsignedByte();
        CrossSection section = buffer.readEnum(CrossSection.class);
        List<CurvePoint> points = readPoints(buffer);
        return new Curve(id, Block.stateById(state), points, thickness, section, readBends(buffer, points.size() - 1));
    }

    private static void writeBends(FriendlyByteBuf buffer, List<CurveBend> bends) {
        int count = 0;
        for (CurveBend bend : bends) {
            if (!bend.isEmpty()) {
                count++;
            }
        }
        buffer.writeVarInt(count);
        for (int span = 0; span < bends.size(); span++) {
            CurveBend bend = bends.get(span);
            if (!bend.isEmpty()) {
                buffer.writeVarInt(span);
                buffer.writeFloat((float) bend.offset().x);
                buffer.writeFloat((float) bend.offset().y);
                buffer.writeFloat((float) bend.offset().z);
                buffer.writeFloat((float) bend.center());
            }
        }
    }

    private static List<CurveBend> readBends(FriendlyByteBuf buffer, int spanCount) {
        int count = buffer.readVarInt();
        if (count < 0 || count > spanCount) {
            throw new DecoderException("Invalid curve bend count");
        }
        if (count == 0) {
            return List.of();
        }
        List<CurveBend> bends = new ArrayList<>(Collections.nCopies(spanCount, CurveBend.NONE));
        boolean[] seen = new boolean[spanCount];
        for (int i = 0; i < count; i++) {
            int span = buffer.readVarInt();
            if (span < 0 || span >= spanCount || seen[span]) {
                throw new DecoderException("Invalid curve bend span");
            }
            seen[span] = true;
            try {
                bends.set(span, new CurveBend(new Vec3(buffer.readFloat(), buffer.readFloat(), buffer.readFloat()), buffer.readFloat()));
            } catch (IllegalArgumentException exception) {
                throw new DecoderException("Invalid curve bend", exception);
            }
        }
        return CurveBend.compact(bends);
    }

    public static void writePoints(FriendlyByteBuf buffer, List<CurvePoint> points) {
        buffer.writeVarInt(points.size());
        Vec3 origin = points.getFirst().position();
        buffer.writeDouble(origin.x);
        buffer.writeDouble(origin.y);
        buffer.writeDouble(origin.z);
        for (int i = 0; i < points.size(); i++) {
            CurvePoint point = points.get(i);
            if (i > 0) {
                Vec3 offset = point.position().subtract(origin);
                buffer.writeFloat((float) offset.x);
                buffer.writeFloat((float) offset.y);
                buffer.writeFloat((float) offset.z);
            }
            buffer.writeFloat((float) point.normal().x);
            buffer.writeFloat((float) point.normal().y);
            buffer.writeFloat((float) point.normal().z);
        }
    }

    public static List<CurvePoint> readPoints(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 2 || size > CurveLimits.MAX_POINTS) {
            throw new DecoderException("Invalid curve point count");
        }
        Vec3 origin = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        List<CurvePoint> points = new ArrayList<>(size);
        try {
            for (int i = 0; i < size; i++) {
                Vec3 position = i == 0 ? origin : origin.add(buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
                points.add(new CurvePoint(position, new Vec3(buffer.readFloat(), buffer.readFloat(), buffer.readFloat())));
            }
        } catch (IllegalArgumentException exception) {
            throw new DecoderException("Invalid curve coordinates", exception);
        }
        return List.copyOf(points);
    }
}
