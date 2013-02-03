package org.dynmap.forge;

import net.minecraft.src.Chunk;
import net.minecraft.src.BiomeGenBase;
import net.minecraft.src.ExtendedBlockStorage;
import net.minecraft.src.NBTTagCompound;
import net.minecraft.src.NBTTagList;
import net.minecraft.src.NibbleArray;
import net.minecraft.src.World;
/**
 * Represents a static, thread-safe snapshot of chunk of blocks
 * Purpose is to allow clean, efficient copy of a chunk data to be made, and then handed off for processing in another thread (e.g. map rendering)
 */
public class ChunkSnapshot
{
    private final int x, z;
    private final short[][] blockids; /* Block IDs, by section */
    private final byte[][] blockdata;
    private final byte[][] skylight;
    private final byte[][] emitlight;
    private final boolean[] empty;
    private final int[] hmap; // Height map
    private final byte[] biome;
    private final long captureFulltime;
    private final int sectionCnt;

    private static final int BLOCKS_PER_SECTION = 16 * 16 * 16;
    private static final int COLUMNS_PER_CHUNK = 16 * 16;
    private static final short[] emptyIDs = new short[BLOCKS_PER_SECTION];
    private static final byte[] emptyData = new byte[BLOCKS_PER_SECTION / 2];
    private static final byte[] fullData = new byte[BLOCKS_PER_SECTION / 2];

    static
    {
        for (int i = 0; i < fullData.length; i++)
        {
            fullData[i] = (byte)0xFF;
        }
    }

    /**
     * Construct empty chunk snapshot
     *
     * @param x
     * @param z
     */
    public ChunkSnapshot(int height, int x, int z, long captime)
    {
        this.x = x;
        this.z = z;
        this.captureFulltime = captime;
        this.biome = new byte[COLUMNS_PER_CHUNK];
        this.sectionCnt = height / 16;
        /* Allocate arrays indexed by section */
        this.blockids = new short[this.sectionCnt][];
        this.blockdata = new byte[this.sectionCnt][];
        this.skylight = new byte[this.sectionCnt][];
        this.emitlight = new byte[this.sectionCnt][];
        this.empty = new boolean[this.sectionCnt];

        /* Fill with empty data */
        for (int i = 0; i < this.sectionCnt; i++)
        {
            this.empty[i] = true;
            this.blockids[i] = emptyIDs;
            this.blockdata[i] = emptyData;
            this.emitlight[i] = emptyData;
            this.skylight[i] = fullData;
        }

        /* Create empty height map */
        this.hmap = new int[16 * 16];
    }


    public ChunkSnapshot(NBTTagCompound nbt) {
        this.x = nbt.getInteger("xPos");
        this.z = nbt.getInteger("zPos");
        this.captureFulltime = 0;
        this.hmap = nbt.getIntArray("HeightMap");
        this.sectionCnt = 16;
        /* Allocate arrays indexed by section */
        this.blockids = new short[this.sectionCnt][];
        this.blockdata = new byte[this.sectionCnt][];
        this.skylight = new byte[this.sectionCnt][];
        this.emitlight = new byte[this.sectionCnt][];
        this.empty = new boolean[this.sectionCnt];
        /* Fill with empty data */
        for (int i = 0; i < this.sectionCnt; i++) {
            this.empty[i] = true;
            this.blockids[i] = emptyIDs;
            this.blockdata[i] = emptyData;
            this.emitlight[i] = emptyData;
            this.skylight[i] = fullData;
        }
        /* Get sections */
        NBTTagList sect = nbt.getTagList("Sections");
        for (int i = 0; i < sect.tagCount(); i++) {
            NBTTagCompound sec = (NBTTagCompound) sect.tagAt(i);
            byte secnum = sec.getByte("Y");
            byte[] lsb_bytes = sec.getByteArray("Blocks");
            short[] blkids = new short[BLOCKS_PER_SECTION];
            this.blockids[secnum] = blkids;
            int len = BLOCKS_PER_SECTION;
            if(len > lsb_bytes.length) len = lsb_bytes.length;
            for(int j = 0; j < len; j++) {
                blkids[j] = (short)(0xFF & lsb_bytes[j]); 
            }
            if (sec.hasKey("Add")) {    /* If additional data, add it */
                byte[] msb = sec.getByteArray("Add");
                len = BLOCKS_PER_SECTION / 2;
                if(len > msb.length) len = msb.length;
                for (int j = 0; j < len; j++) {
                    short b = (short)(msb[j] & 0xFF);
                    if (b == 0) {
                        continue;
                    }
                    blkids[j << 1] |= (b & 0x0F) << 8;
                    blkids[(j << 1) + 1] |= (b & 0xF0) << 4;
                }
            }
            this.blockdata[secnum] = sec.getByteArray("Data");
            this.emitlight[secnum] = sec.getByteArray("BlockLight");
            if (sec.hasKey("SkyLight")) {
                this.skylight[secnum] = sec.getByteArray("SkyLight");
            }
            this.empty[secnum] = false;
        }
        /* Get biome data */
        if (nbt.hasKey("Biomes")) {
            this.biome = nbt.getByteArray("Biomes");
        }
        else {
            this.biome = new byte[COLUMNS_PER_CHUNK];
        }
    }

    public ChunkSnapshot(Chunk chunk)
    {
        this(chunk.worldObj.getHeight(), chunk.xPosition, chunk.zPosition, chunk.worldObj.getWorldTime());
        /* Copy biome data */
        System.arraycopy(chunk.getBiomeArray(), 0, biome, 0, COLUMNS_PER_CHUNK);
        ExtendedBlockStorage[] ebs = chunk.getBlockStorageArray();

        /* Copy sections */
        for (int i = 0; i < this.sectionCnt; i++)
        {
            ExtendedBlockStorage eb = ebs[i];

            if ((eb != null) && (eb.isEmpty() == false))
            {
                this.empty[i] = false;
                /* Copy base IDs */
                byte[] baseids = eb.getBlockLSBArray();
                short blockids[] = new short[BLOCKS_PER_SECTION];

                for (int j = 0; j < BLOCKS_PER_SECTION; j++)
                {
                    blockids[j] = (short)(baseids[j] & 0xFF);
                }

                /* Add MSB data, if section has any */
                NibbleArray msb = eb.getBlockMSBArray();

                if (msb != null)
                {
                    byte[] extids = msb.data;

                    for (int j = 0; j < extids.length; j++)
                    {
                        short b = (short)(extids[j] & 0xFF);

                        if (b == 0)
                        {
                            continue;
                        }

                        blockids[j << 1] |= (b & 0x0F) << 8;
                        blockids[(j << 1) + 1] |= (b & 0xF0) << 4;
                    }
                }

                this.blockids[i] = blockids;
                /* Copy block data */
                this.blockdata[i] = new byte[BLOCKS_PER_SECTION / 2];
                System.arraycopy(eb.getMetadataArray().data, 0, this.blockdata[i], 0, BLOCKS_PER_SECTION / 2);
                /* Copy block lighting data */
                this.emitlight[i] = new byte[BLOCKS_PER_SECTION / 2];
                System.arraycopy(eb.getBlocklightArray().data, 0, this.emitlight[i], 0, BLOCKS_PER_SECTION / 2);
                /* Copy sky lighting data */
                this.skylight[i] = new byte[BLOCKS_PER_SECTION / 2];
                System.arraycopy(eb.getSkylightArray().data, 0, this.skylight[i], 0, BLOCKS_PER_SECTION / 2);
            }
        }

        /* Save height map */
        System.arraycopy(chunk.heightMap, 0, this.hmap, 0, hmap.length);
    }

    public int getX()
    {
        return x;
    }

    public int getZ()
    {
        return z;
    }

    public int getBlockTypeId(int x, int y, int z)
    {
        return blockids[y >> 4][((y & 0xF) << 8) | (z << 4) | x];
    }

    public int getBlockData(int x, int y, int z)
    {
        int off = ((y & 0xF) << 7) | (z << 3) | (x >> 1);
        return (blockdata[y >> 4][off] >> ((x & 1) << 2)) & 0xF;
    }

    public int getBlockSkyLight(int x, int y, int z)
    {
        int off = ((y & 0xF) << 7) | (z << 3) | (x >> 1);
        return (skylight[y >> 4][off] >> ((x & 1) << 2)) & 0xF;
    }

    public int getBlockEmittedLight(int x, int y, int z)
    {
        int off = ((y & 0xF) << 7) | (z << 3) | (x >> 1);
        return (emitlight[y >> 4][off] >> ((x & 1) << 2)) & 0xF;
    }

    public int getHighestBlockYAt(int x, int z)
    {
        return hmap[z << 4 | x];
    }

    public int getBiome(int x, int z)
    {
        return biome[z << 4 | x];
    }

    public final long getCaptureFullTime()
    {
        return captureFulltime;
    }

    public boolean isSectionEmpty(int sy)
    {
        return empty[sy];
    }
}
