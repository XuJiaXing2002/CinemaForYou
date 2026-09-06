package com.cinemaforyou.data;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * 屏幕定义（不可变 record）。
 *
 * <p>由两个对角点 {@code corner1}/{@code corner2} + 朝向 {@code orientation}
 * 完整描述一块矩形屏幕区域。{@code sourceUrl} 可能为空（IDLE 屏）或
 * 形如 {@code https://...}、{@code file:videos/foo.mp4} 等的源描述。
 *
 * <p>{@code customId} 是玩家创建时可选填的短名称（如 {@code main}、{@code 大厅屏}），
 * 命令与 GUI 中可替代 UUID 使用；为空串表示未命名，只能用 UUID 引用。
 *
 * <p>音频：{@code audioRangeBlocks} 为本屏声音最大可听距离（0 = 跟随客户端全局默认）；
 * {@code audioFalloffTenths} 为距离衰减指数 ×10（0 = 跟随全局默认，10 = 线性衰减）。
 *
 * <p>曲面：{@code curvatureType} 0=平面 1=水平凸弧 2=水平凹弧(边缘向观众弯) 3=双向凸弧(球面) 4=双向凹弧；
 * 四边弧度独立：{@code curvDegL}/{@code curvDegR}（左/右弧）、
 * {@code curvDegT}/{@code curvDegB}（上/下弧），单位度（0=该边不弯），可不对称。
 *
 * <p>倾斜：{@code tiltDegH}（左右，绕屏幕垂直轴，-90..90）、
 * {@code tiltDegV}（上下俯仰，绕屏幕水平轴，-90..90）。
 *
 * <p>序列化：通过 {@link #STREAM_CODEC} 写入/读取 RegistryFriendlyByteBuf，
 * 供网络包 {@link com.cinemaforyou.network.ScreenSyncPayload} 使用。
 */
public record CinemaScreen(
        UUID id,
        BlockPos corner1,
        BlockPos corner2,
        ScreenOrientation orientation,
        String sourceUrl,
        String ownerId,
        long createdAt,
        String customId,
        int brightnessPercent,
        int volumePercent,
        int resolutionHeight,
        int displayScalePercent,
        int audioRangeBlocks,
        int audioFalloffTenths,
        int curvatureType,
        int curvDegL,
        int curvDegR,
        int curvDegT,
        int curvDegB,
        int tiltDegH,
        int tiltDegV
) {
    /** 供旧调用方的兼容构造（customId 为空，显示与音频均为默认值）。 */
    public CinemaScreen(UUID id, BlockPos corner1, BlockPos corner2,
                        ScreenOrientation orientation, String sourceUrl,
                        String ownerId, long createdAt) {
        this(id, corner1, corner2, orientation, sourceUrl, ownerId, createdAt, "",
                100, 100, 720, 100, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** 供旧调用方的兼容构造（带 customId，使用默认屏幕设置）。 */
    public CinemaScreen(UUID id, BlockPos corner1, BlockPos corner2,
                        ScreenOrientation orientation, String sourceUrl,
                        String ownerId, long createdAt, String customId) {
        this(id, corner1, corner2, orientation, sourceUrl, ownerId, createdAt, customId,
                100, 100, 720, 100, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** 供旧调用方的兼容构造（无音频/曲率/倾斜覆盖，跟随默认）。 */
    public CinemaScreen(UUID id, BlockPos corner1, BlockPos corner2,
                        ScreenOrientation orientation, String sourceUrl,
                        String ownerId, long createdAt, String customId,
                        int brightnessPercent, int volumePercent,
                        int resolutionHeight, int displayScalePercent) {
        this(id, corner1, corner2, orientation, sourceUrl, ownerId, createdAt, customId,
                brightnessPercent, volumePercent, resolutionHeight, displayScalePercent,
                0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** 用于"仅改某字段"的复制方法。 */
    public CinemaScreen withSourceUrl(String newUrl) {
        return new CinemaScreen(id, corner1, corner2, orientation, newUrl,
                ownerId, createdAt, customId,
                brightnessPercent, volumePercent, resolutionHeight, displayScalePercent,
                audioRangeBlocks, audioFalloffTenths,
                curvatureType, curvDegL, curvDegR, curvDegT, curvDegB, tiltDegH, tiltDegV);
    }

    /** 更新屏幕显示与播放设置（音频字段保持不变）。 */
    public CinemaScreen withSettings(int newBrightnessPercent, int newVolumePercent,
                                     int newResolutionHeight, int newDisplayScalePercent) {
        return new CinemaScreen(id, corner1, corner2, orientation, sourceUrl,
                ownerId, createdAt, customId,
                clamp(newBrightnessPercent, 0, 100),
                clamp(newVolumePercent, 0, 100),
                clampResolution(newResolutionHeight),
                clamp(newDisplayScalePercent, 25, 200),
                audioRangeBlocks, audioFalloffTenths,
                curvatureType, curvDegL, curvDegR, curvDegT, curvDegB, tiltDegH, tiltDegV);
    }

    /** 更新屏幕音频设置（显示字段保持不变）。0 表示跟随客户端全局默认。 */
    public CinemaScreen withAudioSettings(int newAudioRangeBlocks, int newAudioFalloffTenths) {
        return new CinemaScreen(id, corner1, corner2, orientation, sourceUrl,
                ownerId, createdAt, customId,
                brightnessPercent, volumePercent, resolutionHeight, displayScalePercent,
                clamp(newAudioRangeBlocks, 0, 512),
                clamp(newAudioFalloffTenths, 0, 100),
                curvatureType, curvDegL, curvDegR, curvDegT, curvDegB, tiltDegH, tiltDegV);
    }

    /** 更新屏幕曲面设置（四边弧度独立；其余字段保持不变）。默认 0=平面不弯曲。 */
    public CinemaScreen withCurvatureSettings(int newCurvatureType,
                                              int newCurvDegL, int newCurvDegR,
                                              int newCurvDegT, int newCurvDegB) {
        return new CinemaScreen(id, corner1, corner2, orientation, sourceUrl,
                ownerId, createdAt, customId,
                brightnessPercent, volumePercent, resolutionHeight, displayScalePercent,
                audioRangeBlocks, audioFalloffTenths,
                clamp(newCurvatureType, 0, 4),
                clamp(newCurvDegL, 0, 90),
                clamp(newCurvDegR, 0, 90),
                clamp(newCurvDegT, 0, 90),
                clamp(newCurvDegB, 0, 90),
                tiltDegH, tiltDegV);
    }

    /** 更新屏幕倾斜设置（其余字段保持不变）。±90° 以内。 */
    public CinemaScreen withTiltSettings(int newTiltDegH, int newTiltDegV) {
        return new CinemaScreen(id, corner1, corner2, orientation, sourceUrl,
                ownerId, createdAt, customId,
                brightnessPercent, volumePercent, resolutionHeight, displayScalePercent,
                audioRangeBlocks, audioFalloffTenths,
                curvatureType, curvDegL, curvDegR, curvDegT, curvDegB,
                clamp(newTiltDegH, -90, 90),
                clamp(newTiltDegV, -90, 90));
    }

    /** 该屏是否启用曲面（类型非平面且对应边有弧度）。 */
    public boolean isCurved() {
        if (curvatureType <= 0) return false;
        return switch (curvatureType) {
            case 1, 2 -> curvDegL > 0 || curvDegR > 0;
            case 3, 4 -> curvDegL > 0 || curvDegR > 0 || curvDegT > 0 || curvDegB > 0;
            default -> false;
        };
    }

    /** 显示用短名称：有自定义名用之，否则用 UUID 前 8 位。 */
    public String displayName() {
        return (customId == null || customId.isEmpty())
                ? id.toString().substring(0, 8)
                : customId;
    }

    /** 屏幕宽（沿屏幕平面水平方向，块数）。 */
    public int width() {
        return switch (orientation) {
            case AXIS_X -> Math.abs(corner2.getZ() - corner1.getZ()) + 1;
            case AXIS_Y -> Math.abs(corner2.getX() - corner1.getX()) + 1;
            case AXIS_Z -> Math.abs(corner2.getX() - corner1.getX()) + 1;
        };
    }

    /** 屏幕高（沿屏幕平面垂直方向，块数）。 */
    public int height() {
        return switch (orientation) {
            case AXIS_X -> Math.abs(corner2.getY() - corner1.getY()) + 1;
            case AXIS_Y -> Math.abs(corner2.getZ() - corner1.getZ()) + 1;
            case AXIS_Z -> Math.abs(corner2.getY() - corner1.getY()) + 1;
        };
    }

    /** 屏幕中心点（世界坐标）。 */
    public BlockPos center() {
        return new BlockPos(
                (corner1.getX() + corner2.getX()) / 2,
                (corner1.getY() + corner2.getY()) / 2,
                (corner1.getZ() + corner2.getZ()) / 2
        );
    }

    /** 屏幕面积（块²），用于限制最大尺寸。 */
    public int area() {
        return width() * height();
    }

    /** 判断指定方块是否落在屏幕所覆盖的块面上。 */
    public boolean contains(BlockPos pos) {
        int minX = Math.min(corner1.getX(), corner2.getX());
        int maxX = Math.max(corner1.getX(), corner2.getX());
        int minY = Math.min(corner1.getY(), corner2.getY());
        int maxY = Math.max(corner1.getY(), corner2.getY());
        int minZ = Math.min(corner1.getZ(), corner2.getZ());
        int maxZ = Math.max(corner1.getZ(), corner2.getZ());
        return switch (orientation) {
            case AXIS_X -> pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
            case AXIS_Y -> pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
            case AXIS_Z -> pos.getZ() >= minZ && pos.getZ() <= maxZ
                    && pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY;
        };
    }

    /** 屏幕声源中心（世界坐标，取屏幕中心 + 0.5 块中心点）。 */
    public net.minecraft.world.phys.Vec3 audioAnchor() {
        BlockPos c = center();
        return new net.minecraft.world.phys.Vec3(c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5);
    }

    /**
     * 屏幕"可见面"上的一点（用于隔音射线终点）。
     *
     * <p>不能直接用 {@link #audioAnchor()}：贴在墙上的屏幕其中心块在墙内，
     * 射线终点会落在实心方块里造成误判。这里取屏幕显示面中心并略微前移。
     */
    public net.minecraft.world.phys.Vec3 audioOcclusionPoint() {
        int minX = Math.min(corner1.getX(), corner2.getX());
        int maxX = Math.max(corner1.getX(), corner2.getX());
        int minY = Math.min(corner1.getY(), corner2.getY());
        int maxY = Math.max(corner1.getY(), corner2.getY());
        int minZ = Math.min(corner1.getZ(), corner2.getZ());
        int maxZ = Math.max(corner1.getZ(), corner2.getZ());
        double midX = (minX + maxX + 1) / 2.0;
        double midY = (minY + maxY + 1) / 2.0;
        double midZ = (minZ + maxZ + 1) / 2.0;
        return switch (orientation) {
            case AXIS_Z -> new net.minecraft.world.phys.Vec3(midX, midY, maxZ + 1.06);
            case AXIS_X -> new net.minecraft.world.phys.Vec3(maxX + 1.06, midY, midZ);
            case AXIS_Y -> new net.minecraft.world.phys.Vec3(midX, maxY + 1.06, midZ);
        };
    }

    /** 该屏音频最大可听距离（0 = 跟随全局默认）。 */
    public int effectiveAudioRange() {
        return audioRangeBlocks > 0 ? audioRangeBlocks : 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampResolution(int value) {
        return switch (value) {
            case 360, 480, 720, 1080, 1440, 2160 -> value;
            default -> value <= 360 ? 360
                    : value <= 480 ? 480
                    : value <= 720 ? 720
                    : value <= 1080 ? 1080
                    : value <= 1440 ? 1440 : 2160;
        };
    }

    // ───────────── 序列化 ─────────────

    /** StreamCodec：将 CinemaScreen 序列化到 RegistryFriendlyByteBuf。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, CinemaScreen> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public CinemaScreen decode(RegistryFriendlyByteBuf buf) {
                    UUID id = buf.readUUID();
                    BlockPos c1 = buf.readBlockPos();
                    BlockPos c2 = buf.readBlockPos();
                    ScreenOrientation orient = ScreenOrientation.values()[buf.readVarInt()];
                    String url = buf.readUtf();
                    String owner = buf.readUtf();
                    long created = buf.readLong();
                    String custom = buf.readUtf();
                    int brightness = buf.readVarInt();
                    int volume = buf.readVarInt();
                    int resolution = buf.readVarInt();
                    int scale = buf.readVarInt();
                    int audioRange = buf.readVarInt();
                    int audioFalloff = buf.readVarInt();
                    int curvType = buf.readVarInt();
                    int curvL = buf.readVarInt();
                    int curvR = buf.readVarInt();
                    int curvT = buf.readVarInt();
                    int curvB = buf.readVarInt();
                    int tiltH = buf.readVarInt();
                    int tiltV = buf.readVarInt();
                    return new CinemaScreen(id, c1, c2, orient, url, owner, created, custom,
                            brightness, volume, resolution, scale,
                            audioRange, audioFalloff,
                            curvType, curvL, curvR, curvT, curvB, tiltH, tiltV);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, CinemaScreen s) {
                    buf.writeUUID(s.id);
                    buf.writeBlockPos(s.corner1);
                    buf.writeBlockPos(s.corner2);
                    buf.writeVarInt(s.orientation.ordinal());
                    buf.writeUtf(s.sourceUrl);
                    buf.writeUtf(s.ownerId);
                    buf.writeLong(s.createdAt);
                    buf.writeUtf(s.customId == null ? "" : s.customId);
                    buf.writeVarInt(s.brightnessPercent);
                    buf.writeVarInt(s.volumePercent);
                    buf.writeVarInt(s.resolutionHeight);
                    buf.writeVarInt(s.displayScalePercent);
                    buf.writeVarInt(s.audioRangeBlocks);
                    buf.writeVarInt(s.audioFalloffTenths);
                    buf.writeVarInt(s.curvatureType);
                    buf.writeVarInt(s.curvDegL);
                    buf.writeVarInt(s.curvDegR);
                    buf.writeVarInt(s.curvDegT);
                    buf.writeVarInt(s.curvDegB);
                    buf.writeVarInt(s.tiltDegH);
                    buf.writeVarInt(s.tiltDegV);
                }
            };

    /** List 编解码器（用于 ScreenSyncPayload 的全量同步）。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, java.util.List<CinemaScreen>> LIST_STREAM_CODEC =
            STREAM_CODEC.apply(ByteBufCodecs.list());
}
