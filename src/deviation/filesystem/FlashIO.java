package deviation.filesystem;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import de.waldheinz.fs.BlockDevice;
import deviation.Dfu;
import deviation.DfuDevice;
import deviation.Progress;
import deviation.Sector;
import deviation.misc.Range;
import deviation.misc.Sha;

public class FlashIO implements BlockDevice
{

    private static final Logger LOG = Logger.getLogger(FlashIO.class.getName());

    //private final RamDisk ram;
    private final byte[] ram;
    private final ByteBuffer rambuf;
    private final DfuDevice dev;
    private final boolean[] cached;
    private final String[] chksum;
    private final List<Range> sectorMap;
    private final long startOffset;
    private final long memAddress;
    private final boolean invert;
    private final int fsSectorSize;
    private Progress progress;
    private long totalBytes = 0;
    private long totalTime = 0;


    public FlashIO(DfuDevice dev, long start_address, boolean invert, int fs_sector_size, Progress progress)
    {
    	memAddress = dev.Memory().findStartingAddress();
        startOffset = start_address - memAddress;
        fsSectorSize = fs_sector_size;
        this.progress = progress;
        sectorMap = new ArrayList<Range>();
        
        
        List<Sector> sectors = dev.Memory().segments().get(0).sectors();
        for (Sector sector: sectors) {
        	Range.createSequentialRanges(sectorMap, sector.start(), sector.size(), sector.count());
        }
        long mem_size = 1 + sectorMap.get(sectorMap.size()-1).end() - sectorMap.get(0).start();
        int sector_count = sectorMap.size();
        cached = new boolean[sector_count];
        chksum = new String[sector_count];
        
    	ram = new byte[(int)mem_size];
    	rambuf = ByteBuffer.wrap(ram);
        this.dev = dev;
        this.invert = invert;
    }
    
    public void setProgress(Progress progress) {
    	this.progress = progress;
    }
    public void close() throws IOException {
    	int sector_num;
    	for (sector_num = 0; sector_num < cached.length; sector_num++) {
    		if (progress != null && progress.cancelled())
    			break;
            if (cached[sector_num]) {
            	Range range = sectorMap.get(sector_num);
            	byte [] data = Arrays.copyOfRange(ram, (int)(range.start() - memAddress),  (int)(1 + range.end() - memAddress));
            	String newchksum = Sha.md5(data);
            	LOG.finest(String.format("0x%08x Read: %s Write: %s", range.start(), chksum[sector_num], newchksum));
            	if (chksum[sector_num] != null && chksum[sector_num].equals(newchksum)) {
            		//Data hasn't changed, no need to write it out
            		continue;
            	}
            	//dump(Long.toHexString(range.start()), data);
                if (invert) {
                    data = FSUtils.invert(data);
                }
                Dfu.sendToDevice(dev,  range.start(), data, progress);
                chksum[sector_num] = newchksum;
            }
    	}
    }
    public void flush() { LOG.finest("flush");}
    public int getSectorSize() throws IOException { return fsSectorSize; }
    public long getSize() throws IOException { return ram.length - startOffset; }
    public boolean isClosed() { return false; }
    public boolean isReadOnly() { return false; }
    public void markAllCached() {
    	// This is used for the format operation to prevent disk reads
    	for (int i = 0; i < cached.length; i++) {
    		cached[i] = true;
    	}
    }
    /*
    private void dump(String name, byte data[]) {
        try{
            File f = new File(name);
            FileOutputStream fop = new FileOutputStream(f);
            // if file doesnt exists, then create it
            if (!f.exists()) {
                f.createNewFile();
            }
            fop.write(data);
            fop.flush();
            fop.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    */
    private void cache(int sector_num) {
    	Range range = sectorMap.get(sector_num);
        LOG.finest(String.format(("Cache of 0x%08x : %d"), range.start(), cached[sector_num] ? 1 : 0));
        if (! cached[sector_num]) {
        	final long startTime = System.currentTimeMillis();
            byte[] data = Dfu.fetchFromDevice(dev, range.start(), (int)range.size());
            final long endTime = System.currentTimeMillis();
            totalBytes += range.size();
            totalTime += endTime - startTime;
            LOG.finest(String.format("Bytes/sec: %.6f Avg: %.6f", 1000.0 *range.size()/(endTime - startTime), 1000.0*totalBytes/totalTime));
            if (invert) {
                data = FSUtils.invert(data);
            }
            System.arraycopy(data, 0, ram, (int)(range.start() - memAddress), data.length);
            cached[sector_num] = true;
            chksum[sector_num] = Sha.md5(data);
        }
    }
    public void read(long devOffset, ByteBuffer dest) throws IOException {
        long start = startOffset + devOffset;
        long end = start + dest.remaining();
        int curSector = Range.getIndex(sectorMap, (int)(memAddress+start));
        long lastSector = Range.getIndex(sectorMap, (int)(memAddress+end));
        while (curSector <= lastSector) {
            cache(curSector);
            curSector++;
        }
        rambuf.limit((int)end);
        rambuf.position((int)start);
        dest.put(rambuf);
    }

    public void write(long devOffset, ByteBuffer src) throws IOException {
        long start = startOffset + devOffset;
        long end = start + src.remaining();
        int curSector = Range.getIndex(sectorMap, (int)(start+memAddress));
        int lastSector = Range.getIndex(sectorMap, (int)(end+memAddress)-1);
        //Reading is cheaper than writing, so cache all sectors
        for (int i = curSector; i <= lastSector; i++) {
        	cache(i);
        }
        //if (start > sectorMap.get(curSector).start()-memAddress) {
        //    //Not at a sector boundary
        //    cache(curSector);
        //}
        //if (end <  sectorMap.get(lastSector).end()-memAddress) {
        //    //End is not on a sector boundary;
        //    cache(lastSector);
        //}
        rambuf.limit((int)end);
        rambuf.position((int)start);
        rambuf.put(src);
        while (curSector <= lastSector) {
        	cached[curSector] = true;
        	curSector++;
        }  
    }
}
