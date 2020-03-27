/**
 * Cache.java
 * @author Jiayue Mao
 * Andrew ID: jiayuem
 */

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Cache {

    public static String cachePath;
    public static long cacheSize;

    /* chunk size used in copy files */
    public static final int CHUNKSIZE = 8 * 1024;
    
    /* current allocated memory size in the cache */
    public long curSize;
    
    /* map contains path as key, a linkedlist of all the read copies of the poth as a value */
    public ConcurrentHashMap<String, LinkedList<CacheFile>> pathCopyMap;
    
    /* map contains path as key, the file version time of the path as a value */
    public ConcurrentHashMap<String, Long> pathTimeMap;
    
    /* map contains file descriptor as a key, a corresponding CacheFile object as a value */
    public ConcurrentHashMap<Integer, CacheFile> fdCopyMap;
    
    /* map contains path as a key, the corresponding non-copy CacheFile object as a value */
    public ConcurrentHashMap<String, CacheFile> pathOrigFileMap;
    
    /* a linked list representation of lru */
    public LinkedList<CacheFile> lruList;  // ranked from the lastest used to the least used

    /**
     * Cache Constructor
     * @param path       the path of local cache
     * @param size       the limit of cache size
     */
    public Cache(String path, long size) {
        cachePath = path;
        cacheSize = size;
        curSize = 0;
        pathCopyMap = new ConcurrentHashMap<>();
        pathTimeMap = new ConcurrentHashMap<>();
        fdCopyMap = new ConcurrentHashMap<>();
        pathOrigFileMap = new ConcurrentHashMap<>();
        lruList = new LinkedList<>();
    }

    /**
     * createCachePath: create absolute path of file in the cache
     * @param path         original file
     * @return absolute path
     */
    private String createCachePath(String path) {
        StringBuilder sb = new StringBuilder(cachePath);
        sb.append("/");
        sb.append(path);
        return sb.toString();
    }

    /**
     * dealWithSubdirs: deal with file path with subdirectories.
     * @param path       input path
     * @return output transformed path
     */
    public String dealWithSubdirs(String path) {
        String[] split = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < split.length - 1; i++) {
            sb.append(split[i]);
            sb.append("-");
        }
        sb.append(split[split.length - 1]);
        return sb.toString();
    }

    /**
     * createNewCopy: create a copy of the file in the cache
     * @param cachePath       the absolute path of a file in the cache
     * @param fd              file descriptor
     * @return the absolute path of new copy file
     */
    private synchronized String createNewCopy(String cachePath, int fd) {
        String newPath = cachePath + fd;
        FileOutputStream output = null;
        FileInputStream input = null;
        try {
            output = new FileOutputStream(newPath);
            input = new FileInputStream(cachePath);
            byte[] buf = new byte[CHUNKSIZE];
            int readLen = 0;
            while ((readLen = input.read(buf)) > 0) {
                output.write(buf, 0, readLen);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                output.close();
                input.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return newPath;
    }

    /**
     * copyFileToNewPath: create a new copied CacheFile object with a new path.
     * @param caFile       original CacheFile object
     * @param newPath      new absolute path stored in cache
     * @return a copy of the original CacheFile object but with the new path
     */
    private synchronized CacheFile copyFileToNewPath(CacheFile caFile, String newPath) {
        CacheFile newFile = new CacheFile(caFile.path, newPath, caFile.modifiedTime);
        newFile.isDir = caFile.isDir;
        newFile.readCnt = 1;
        newFile.readOnly = caFile.readOnly;
        newFile.fileSize = caFile.fileSize;
        return newFile;
    }

    /**
     * pathExist: whether cache has file with such path
     * @param path
     * @return true, if exist; false, if not.
     */
    public boolean pathExist(String path) {
        return pathTimeMap.containsKey(path);
    }

    /**
     * lastModifiedTime: get the last modified time of file with the path in the cache
     * @param path       original path of a file
     * @return the last modified time
     */
    public long lastModifiedTime(String path) {
        if (!pathExist(path)) return -1;
        return pathTimeMap.get(path);
    }

    /**
     * updateTime: update the latest timestamp of the file with the path 
     * @param path       original path of a file
     * @param time       the latest time
     */
    public void updateTime(String path, long time) {
        pathTimeMap.put(path, time);
        if (!pathCopyMap.containsKey(path)) {
            pathCopyMap.put(path, new LinkedList<>());
        }
    }

    /**
     * getLastCopy: get the latest read copy of the path
     * @param path       original path of a file
     * @return CacheFile object
     */
    public CacheFile getLastCopy(String path) {
        if (!pathCopyMap.containsKey(path)) return null;
        if (pathCopyMap.get(path).size() == 0) return null;
        return pathCopyMap.get(path).getLast();
    }

    /**
     * lastCopyIsLatest: whether the last read copy is up-to-date
     * @param path       original path of a file
     * @param time       the current up-to-date time
     * @return true, if latest; false, if not
     */
    public boolean lastCopyIsLatest(String path, long time) {
        if (!pathExist(path)) return false;
        CacheFile last = getLastCopy(path);
        if (last == null) return false;
        if (last.modifiedTime != time) return false;
        if (last.readCnt == 0) return false;
        return true;
    }

    /**
     * incrCacheSize: add to the current allocated size in cache
     * @param size
     * @return true, if value after add will not be larger than cache size limit
     */
    public synchronized boolean incrCacheSize(long size) {
        synchronized (this) {
            if (curSize + size > cacheSize) {
                return false;
            }
            curSize += size;
            return true;
        }
        
    }

    /**
     * evict: evict from the tail of the lru linkedlist to free up demanded size
     * @param size        the size to be freed up
     * @return true, if eviction succeed; false, if failed.
     */
    public synchronized boolean evict(long size) {
        long evictSize = 0;
        synchronized (this) {
            evictSize = 0;
            for (int i = lruList.size() - 1; i >= 0; i--) {
                CacheFile node = lruList.get(i);
                evictSize += node.fileSize;
                if (curSize - evictSize + size <= cacheSize) break;
            }
            if (curSize - evictSize + size > cacheSize) {
                return false;
            }
            evictSize = 0;
            while (lruList.size() != 0) {
                CacheFile last = lruList.removeLast();
                File file = new File(last.realPath);
                file.delete();
                pathOrigFileMap.remove(last.path);
                pathTimeMap.remove(last.path);
                evictSize += last.fileSize;
                if (curSize - evictSize + size <= cacheSize) {
                    break;
                }
            }
            curSize += size - evictSize;
        }
        return true;
    }

    /**
     * moveFromLru: move a block in lru linkedlist, and decrease current size.
     * @param caFile      the CacheFile object to be moved
     */
    public synchronized void moveFromLru(CacheFile caFile) {
        synchronized (this) {
            int index = lruList.indexOf(caFile);
            if (index >= 0) {
                lruList.remove(caFile);
                curSize -= caFile.fileSize;
            }
            
        }
    }

    /**
     * pushNewFile: push a new copy file in the cache
     * 1. If add the size of the copy to current size will not exceed the size limit,
     *    just create a new copy of the original CacheFile object with the path and store in cache.
     * 2. If the above is not, if read only, rename the non-copy file in cache with a copy name, 
     *    read on that file and move the path from lru list.
     * 3. If the above is not, if not read only, evict such size and then create new copy, 
     *    store in cache.
     * @param caFile           original CacheFile object
     * @param fd               file descriptor
     * @param readOnly         if the file is read only
     * @return the new copy (CacheFile object)
     */
    public synchronized CacheFile pushNewFile(CacheFile caFile, int fd, boolean readOnly) {
        String path = caFile.path;
        String cachePath = caFile.realPath;
        String newCachePath = "";
        synchronized (this) {
            if (!incrCacheSize(caFile.fileSize)) {
                if (readOnly) {
                    CacheFile origFile = pathOrigFileMap.get(caFile.path);
                    File file = new File(origFile.realPath);
                    newCachePath = cachePath + fd;
                    File newFile = new File(newCachePath);
                    lruList.remove(origFile);
                    file.renameTo(newFile);
                    pathOrigFileMap.remove(path);
                    pathTimeMap.remove(path);
                    lruList.remove(origFile);
                } else {

                    if (!evict(caFile.fileSize)) {
                        CacheFile errorFile = new CacheFile(null, null, 0);
                        errorFile.error = FileHandling.Errors.ENOMEM;
                        return errorFile;
                    }
                    newCachePath = createNewCopy(cachePath, fd);  
                }
            } else {
                newCachePath = createNewCopy(cachePath, fd);  
            }
        }
        
        CacheFile newCaFile = copyFileToNewPath(caFile, newCachePath);
        if (readOnly)
            pathCopyMap.get(path).add(newCaFile);
        
        fdCopyMap.put(fd, newCaFile);
        return newCaFile;
    }

    /**
     * addReadCnt: add read count to the last read copy.
     * @param path       original path
     * @param fd         file descriptor
     */
    public void addReadCnt(String path, int fd) {
        CacheFile last = pathCopyMap.get(path).getLast();
        last.readCnt++;
        fdCopyMap.put(fd, last);
    }

    /**
     * getFile: get the CacheFile object corresponding with the file descriptor
     * @param fd        file descriptor
     * @return CacheFile object
     */
    public CacheFile getFile(int fd) {
        return fdCopyMap.get(fd);
    }

    /**
     * closeFile: close the related files with a file descriptor.
     * 1. If not read only, if the non-copy version exists, delete the non-copy version,
     *    rename the current copy to non-copy version, move the corresponding block to the
     *    first of lru list.
     * 2. If not read only, if non-copy version not exist, rename the current copy to 
     *    non-copy copy, add a new related CacheFile object to the first of lru list.
     * 3. If read only, check the read count. 
     *    If read count is equal to 0, if the non-copy version exists, delete the copy,
     *    and move to the first of lru list.
     *    If read count is equal to 0, if non-copy version not exist, rename the copy to 
     *    non-copy version, add to first of lru.
     *    If read count is not equal to 0, read count - 1.
     * @param fd         file descriptor
     * @return 0, if succeed.
     */
    public int closeFile(int fd) {
        CacheFile caFile = fdCopyMap.get(fd);
        boolean readOnly = caFile.readOnly;
        String path = caFile.path;
        String pathWithoutSubdir = dealWithSubdirs(path);
        String cacheOrigPath = createCachePath(pathWithoutSubdir);
        File oldFile = new File(cacheOrigPath);
        synchronized (this) {
            if (!readOnly) {
                if(pathOrigFileMap.containsKey(path)) {
                    CacheFile oldCaFile = pathOrigFileMap.get(path);
                    moveFromLru(oldCaFile);
                    oldCaFile.fileSize = caFile.fileSize;
                    lruList.addFirst(oldCaFile);
                    oldFile.delete();
                    File newFile = new File(caFile.realPath);
                    newFile.renameTo(oldFile);
                    caFile.realPath = cacheOrigPath;
                    
                } else {
                    CacheFile newOrigFile = new CacheFile(path, cacheOrigPath, 0);
                    newOrigFile.fileSize = caFile.fileSize;
                    lruList.addFirst(newOrigFile);
                    pathOrigFileMap.put(path, newOrigFile);
                    File newFile = new File(caFile.realPath);
                    newFile.renameTo(oldFile); 
                    
                }   
                File newFile = new File(cacheOrigPath);
                pathTimeMap.put(path, newFile.lastModified());
                
            } else {
                caFile.readCnt--;
                if (caFile.readCnt == 0) {
                    File file = new File(caFile.realPath);
                    if (pathOrigFileMap.containsKey(path)) {
                        curSize -= (long)file.length();
                        file.delete();
                        lruList.remove(pathOrigFileMap.get(path));
                        lruList.addFirst(pathOrigFileMap.get(path));
                    } else {
                        CacheFile newOrigFile = new CacheFile(path, cacheOrigPath, 0);
                        newOrigFile.fileSize = caFile.fileSize;
                        lruList.addFirst(newOrigFile);
                        pathOrigFileMap.put(path, newOrigFile);
                        File newFile = new File(caFile.realPath);
                        newFile.renameTo(oldFile);
                    }
                }
            }
        }
        fdCopyMap.remove(fd);
        return 0;
        
    }

}