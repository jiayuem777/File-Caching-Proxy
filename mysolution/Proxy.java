/**
 * Proxy.java
 * @author Jiayue Mao
 * Andrew ID: jiayuem
 */

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.rmi.*;
import java.lang.Exception;
import java.lang.IllegalArgumentException;
import java.net.MalformedURLException;

class Proxy {

	private static String serverport; // server port
	private static String serverip;  // server IP
	private static String cacheDir;  // cache directory
	private static long cacheSize;   // cache size limit

	private static Cache cache;  // cache object within a proxy
	public static final int MAXCHUNKSIZE = 100000;  // max chunk size

	// used to generate non-repeate file descriptor
	private static AtomicInteger uniqueFD = new AtomicInteger(0);

	/**
	 * connectToServer: connect to server.
	 * @param serverip            Server IP
	 * @param serverport          Server port
	 * @return Server Instance: ServerInf
	 */
	private static ServerInf connectToServer(String serverip, String serverport) {
		String serverUrl = "//" + serverip + ":" + serverport + "/Server";
		ServerInf server = null;
		try {
			server = (ServerInf) Naming.lookup(serverUrl);
		} catch (NotBoundException e1) {
			e1.printStackTrace();
		} catch (RemoteException e2) {
			e2.printStackTrace();
		} catch (MalformedURLException e3) {
			e3.printStackTrace();
		}
		return server;
	}

	/**
	 * FileHandling: deal with file operations of multiple client.
	 */
	private static class FileHandler implements FileHandling {
		
		ServerInf server = null;

		/* map conatains file descriptor as a key, the opened RandomAccessFile as a value */
		HashMap<Integer, RandomAccessFile> fdFileMap;

		/* set containsfile descriptors corresponding with opend directories */
		HashSet<Integer> dirSet;

		/**
		 * FileHandler constructor
		 */
		public FileHandler() {
			try {
				server = connectToServer(serverip, serverport);
			} catch (Exception e) {
				e.printStackTrace();
			}
			fdFileMap = new HashMap<>();
			dirSet = new HashSet<>();
			synchronized (Cache.class) {
				if (cache == null) {
					cache = new Cache(cacheDir, cacheSize);
				}
			}
		}

		/**
		 * createCachePath: create the absolute path stored in cache.
		 * @param path         original path of the file
		 * @return absolute path in the cache
		 */
		private String createCachePath(String path) {
			StringBuilder sb = new StringBuilder();
			sb.append(cacheDir);
			sb.append("/");
			sb.append(path);
			return sb.toString();
		}

		/**
		 * dealWithSubdirs: deal with subdirectories.
		 * @param path        original path of the file
		 * @return absolute path in the cache
		 */
		private String dealWithSubdirs(String path) {
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
		 * readFromServer: read file from server into cache.
		 * @param path         original path of the file
		 * @param o            open option
		 * @return the length of readed file
		 */
		private synchronized int readFromServer(String path, OpenOption o) {
			String pathWithoutSubdir = dealWithSubdirs(path);
			String cachePath = createCachePath(pathWithoutSubdir);

			boolean isDir = false;

			File file = new File(cachePath);
			if (!file.exists()) {
				try {
					if (!file.isDirectory()) {
						file.createNewFile();
					}
				} catch (IOException e) {
					return Errors.ENOENT;
				}
			}

			FileOutputStream outStream = null;
			try {
				outStream = new FileOutputStream(cachePath);
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			}

			int readLen = 0;
			int offset = 0;
			int chunkSize = Math.min ((int) (cacheSize / 10), MAXCHUNKSIZE);
			synchronized (Server.class) {
				while (true) {          // use loop to read chunks of data from server
					Chunk chunk = null;
					try {
						chunk = server.readOnServer(path, offset, chunkSize, o, cacheSize);
						if (chunk.size > 0) {
							outStream.write(chunk.content, 0, chunk.size);
							readLen += chunk.size;
							offset += chunk.size;
							if (!chunk.remain) {
								break;
							}
						} else {
							int ret = chunk.size;
							try {
								outStream.close();
							} catch (IOException e) {
								e.printStackTrace();
							}
							if (ret == Integer.MIN_VALUE) {
								file.mkdirs();
							}
							return ret;
						}
	
					} catch (RemoteException e1) {
						e1.printStackTrace();
					} catch (IOException e2) {
						e2.printStackTrace();
					}
				}
				try {
					outStream.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			
			return readLen;
		}

		/**
		 * sendToServer: send the newest version to server.
		 * @param caFile       CacheFile object
		 * @param fd           file descriptor
		 * @return the length of sended file
		 */
		public synchronized int sendToServer(CacheFile caFile, int fd) {
			FileInputStream input = null;
			try {
				input = new FileInputStream(caFile.realPath);
			} catch (IOException e) {
				e.printStackTrace();
			}
			int chunkSize = 1024 * 8;
			Chunk chunk = new Chunk(chunkSize);
			File file = new File(caFile.realPath);
			int fileLen = (int) file.length();
			int offset = 0;
			synchronized (Server.class) {
				while (offset < fileLen) {
					int readLen = 0;
					try {
						readLen = input.read(chunk.content, 0, chunkSize);
					} catch (IOException e) {
						e.printStackTrace();
					}
					if (readLen < 0) {
						return Errors.EPERM;
					}
					chunk.size = readLen;
					int writeLen = 0;
					try {
						writeLen = server.writeOnServer(caFile.path, chunk, offset);
						if (writeLen < 0) {
							return writeLen;
						}
					} catch (RemoteException e) {
						e.printStackTrace();
					}
					offset += writeLen;
				}
				try {
					input.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}	
			return fileLen;
		}

		/**
		 * open: open a file.
		 * 1. Check on server whether the current version is up-to-date
		 * 2. If up-to-date, if read only, add read count to latest version copy
		 * 3. If up-to-date, not read only, create a new copy of the latest version.
		 * 4. If out-of-date, read the latest version from server
		 * @param path           original path 
		 * @param o              open option
		 * @return file descriptor
		 */
		public synchronized int open( String path, OpenOption o ) {
			boolean readOnly = false;
			boolean isDir = false;

			if (o == OpenOption.READ) {
				readOnly = true;
			}

			long latestTime = 0;
			try {
				latestTime = server.sendModifiedTime(path);
				if (latestTime < 0) {
					return (int)latestTime;
				}
			} catch (RemoteException e) {
				e.printStackTrace();
			}
			

			int retFd = uniqueFD.getAndIncrement();
			RandomAccessFile raf = null;
			CacheFile copy = null;
			synchronized (cache) {
				// to ensure the copy in cache is the newesst
				if (readOnly && cache.lastCopyIsLatest(path, latestTime)) {
					CacheFile lastCopy = cache.getLastCopy(path);
					copy = lastCopy;
					lastCopy.readCnt++;
					cache.fdCopyMap.put(retFd, lastCopy);
				} else {
					String pathWithoutSubdirs = dealWithSubdirs(path);
					String cachePath = createCachePath(pathWithoutSubdirs);
					CacheFile origFile = null;
					if (cache.pathOrigFileMap.containsKey(path)) {
						origFile = cache.pathOrigFileMap.get(path);
						cache.moveFromLru(origFile);
					} else {
						origFile = new CacheFile(path, cachePath, 0);
						cache.pathOrigFileMap.put(path, origFile);
					}
					if (!cache.pathExist(path) || cache.lastModifiedTime(path) != latestTime) {
						// if not exists or not up-to-date, read from server
						int readLen = readFromServer(path, o);
						
						if (readLen < 0) {
							if (readLen == Integer.MIN_VALUE) { // is directory
								isDir = true;
							} else {
								return readLen; // multiple kinds of errors
							}
						}

						origFile.fileSize = readLen;					
						cache.updateTime(path, latestTime);
						if (isDir) {
							fdFileMap.put(retFd, null);
							dirSet.add(retFd);
							return retFd;
						}
					} 
					cache.lruList.addFirst(origFile);
					if (!cache.incrCacheSize(origFile.fileSize)) {
						if (!cache.evict(origFile.fileSize)) {
							return Errors.ENOMEM;
						}
					}
					CacheFile newCacheFile = new CacheFile(path, cachePath, latestTime);
					newCacheFile.isDir = isDir;
					newCacheFile.readOnly = readOnly;
					File file = new File(cachePath);
					newCacheFile.fileSize = (long)file.length();
					
					CacheFile newCopy = cache.pushNewFile(newCacheFile, retFd, readOnly);
					if (newCopy.error < 0) return newCopy.error;
					copy = newCopy;

				}
			}
				
			try {
				raf = new RandomAccessFile(copy.realPath, "rw");
			} catch (IllegalArgumentException e1) {
				return Errors.EINVAL;
			} catch (FileNotFoundException e2) {
				if (!copy.isDir) {
					return Errors.ENOENT;
				}
			} catch (SecurityException e3) {
				return Errors.EPERM;
			}
			fdFileMap.put(retFd, raf);
			return retFd;

		}

		/**
		 * close: close the file.
		 * If the file is not read only, need to send file content back to server
		 * @param fd     file descriptor
		 * @return if success, return 0. else, return < 0
		 */
		public int close( int fd ) {
			if (fd < 0) {
				return Errors.EBADF;
			}

			if (!fdFileMap.containsKey(fd)) {
				return Errors.ENOENT;
			}

			if (dirSet.contains(fd)) {
				return Errors.EISDIR;
			}
			CacheFile caFile = cache.getFile(fd);
			RandomAccessFile raFile = fdFileMap.get(fd);
			if (raFile == null || caFile == null) {
				fdFileMap.remove(fd);
				return 0;
			}
			
			boolean readOnly = caFile.readOnly;
			// if not read-only, need to push update to server
			if (!readOnly) {
				int sendret = sendToServer(caFile, fd);
				if (sendret < 0) return sendret;
			}
			
			synchronized(cache) {
				int ret = cache.closeFile(fd);
				if (ret < 0) return ret;
			}
			
			fdFileMap.remove(fd);
			return 0;
		}

		/**
		 * write: write to the file in cache.
		 * @param fd        file descriptor
		 * @param buf       write buffer
		 * @return length of write content
		 */
		public long write( int fd, byte[] buf ) {
			long bufLen = buf.length;

			if (buf == null) {
				return Errors.EINVAL;
			}
			if (!fdFileMap.containsKey(fd)) {
				return Errors.EBADF;
			}
			CacheFile caFile = cache.getFile(fd);
			if (caFile == null || caFile.readOnly) {
				return Errors.EBADF;
			}
			if (dirSet.contains(fd)) {
				return Errors.EISDIR;
			}
			RandomAccessFile writeFile = fdFileMap.get(fd);
			long start = 0;
			try {
				start = writeFile.getFilePointer();

			} catch (IOException e1) {
				e1.printStackTrace();
			}
			long writeLen = start + bufLen;
			long origLen = 0;
			try {
				origLen = writeFile.length();
			} catch (IOException e) {
				e.printStackTrace();
			}
			if (writeLen > origLen) {
				synchronized (cache) {
					if (!cache.incrCacheSize(writeLen - origLen)) {
						if (!cache.evict(writeLen - origLen)) {
							return Errors.ENOMEM;
						}
					}
				}
			}
			
			try {
				writeFile.write(buf);
				
			} catch (IOException e) {
				return Errors.EBADF;
			}
			return buf.length;
		}

		/**
		 * read: read from the file in cache.
		 * @param fd      file descriptor
		 * @param buf     read buffer
		 * @return the length of read content
		 */
		public long read( int fd, byte[] buf ) {
			if (buf == null) {
				return Errors.EINVAL;
			}
			if (!fdFileMap.containsKey(fd)) {
				return Errors.EBADF;
			}
			if (dirSet.contains(fd)) {
				return Errors.EISDIR;
			}
			
			RandomAccessFile readFile = fdFileMap.get(fd);
			long result = -1;
			try {
				result = readFile.read(buf);
			} catch (IOException e) {
				return Errors.EPERM;
			}
			if (result == -1) {
				return 0;
			}
			return result;
		}

		/**
		 * lseek: lseek the file in cache.
		 * @param fd       file descriptor
		 * @param pos      lseek position
		 * @param o        lseek option
		 * @return file pointer position after lseek
		 */
		public long lseek( int fd, long pos, LseekOption o ) {
			if (!fdFileMap.containsKey(fd)) {
				return Errors.EBADF;
			}
			CacheFile caFile = cache.getFile(fd);
			RandomAccessFile seekFile = fdFileMap.get(fd);
			long start = 0;
			switch (o) {
				case FROM_START:
					start = 0;
					break;
				case FROM_END:
					try {
						start = seekFile.length();
					} catch (IOException e) {
						return -1;
					}
					break;
				case FROM_CURRENT:
					try {
						start = seekFile.getFilePointer();
					} catch (IOException e) {
						return -1;
					}
					break;
				default:
					return Errors.EINVAL;
					
			}
			long newPos = start + pos;
			if (newPos < 0) {
				return Errors.EINVAL;
			}
			try {
				seekFile.seek(newPos);
				
			} catch (IOException e) {
				return Errors.EPERM;
			}
			return newPos;
		}

		/**
		 * unlink: unlink the file.
		 * @param path     original path of the file
		 * @return if success, return 0; else, return < 0
		 */
		public synchronized int unlink( String path ) {

			String cachePath = createCachePath(path);
			File file = new File(cachePath);

			if (file.isDirectory()) {
				return Errors.EISDIR;
			}

			int ret = 0;
			try {
				ret = server.unlinkOnServer(path);
				if (ret == 0) {
					synchronized(cache) {
						cache.pathTimeMap.remove(path);
						cache.pathCopyMap.remove(path);
						CacheFile unlinkCaFile = cache.pathOrigFileMap.get(path);
						cache.moveFromLru(unlinkCaFile);
						cache.pathOrigFileMap.remove(path);
						file.delete();
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			return ret;
		}

		/**
		 * clientdone: close all the open files and clear memory allocation.
		 */
		public void clientdone() {
			for(RandomAccessFile file : fdFileMap.values()) {
				try {
					if (file != null) file.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			fdFileMap.clear();
			return;
		}

	}
	
	private static class FileHandlingFactory implements FileHandlingMaking {
		public FileHandling newclient() {
			return new FileHandler();
		}
	}
	public static void main(String[] args) throws IOException {
		serverip = args[0];
		serverport = args[1];
		cacheDir = args[2];
		cacheSize = Long.parseLong(args[3]);
		(new RPCreceiver(new FileHandlingFactory())).run();
		
	}
}

