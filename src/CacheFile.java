/**
 * CacheFile.java
 * @author Jiayue Mao
 * Andrew ID: jiayuem
 */

import java.io.Serializable;

public class CacheFile implements Serializable {

    private static final long serialVersionUID = 1L;
    String path;           // original path
    String realPath;       // absolute path stored in cache
    boolean isDir;         // the file is direcotry or not
    boolean readOnly;      // if the file is read only
    int readCnt;           // read count on the file
    long modifiedTime;     // modified time
    int error = 0;         // to transmit error
    long fileSize;         // size of the file
    
    /**
     * CacheFile constructor
     * @param p         original path
     * @param ap        absolute path stored in cache
     * @param time      modified time
     */
    public CacheFile(String p, String ap, long time) {
        path = p;
        realPath = ap;
        modifiedTime = time;
        readCnt = 1;
    }

}