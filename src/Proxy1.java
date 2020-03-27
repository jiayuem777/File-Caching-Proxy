/* Sample skeleton for proxy */

import java.io.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.Exception;
import java.lang.IllegalArgumentException;

class Proxy1 {

	

	private static class FileHandler implements FileHandling {

		private ConcurrentHashMap<Integer,RandomAccessFile> fdFileMap;
		private static AtomicInteger uniqueFD = new AtomicInteger(0);
		private Set<Integer> dirSet;

		public FileHandler() {
			fdFileMap = new ConcurrentHashMap<Integer, RandomAccessFile>();
			dirSet = new HashSet<>();
		}

		public synchronized int open( String path, OpenOption o ) {
			System.out.println("\nOPEN");
			System.out.println("path: " + path);
			System.out.println("open option: " + o);
			String mode = "";
			File check = new File(path);
			switch (o) {
				case READ:
					if (!check.exists()) {
						System.out.println("error: enoent");
						return Errors.ENOENT;
					}
					mode = "r";
					break;
				case WRITE:
					if (!check.exists()) {
						System.out.println("error: enoent");
						return Errors.ENOENT;
					}
					if (check.isDirectory()) {
						System.out.println("error: isdir");
						return Errors.EISDIR;
					}
				case CREATE:
					mode = "rw";
					break;
				case CREATE_NEW:
					if (check.exists()) {
						System.out.println("error: exist");
						return Errors.EEXIST;
					}
					mode = "rw";
					break;		
				default:
					return Errors.EINVAL;
			}

			Integer retFd = new Integer(0);
			try {
				
				RandomAccessFile openFile = new RandomAccessFile(path, mode);
				Integer fd;
				fd = uniqueFD.getAndIncrement();
				retFd = new Integer(fd);
				fdFileMap.put(retFd, openFile);
				
			} catch (IllegalArgumentException e1) {
				System.out.println("error: einval");
				return Errors.EINVAL;
			} catch (FileNotFoundException e2) {
				if (!check.isDirectory()) {
					check.delete();
					return Errors.ENOENT;
				}
				
			} catch (SecurityException e3) {
				System.out.println("error: eperm");
				return Errors.EPERM;
			}
			if (check.isDirectory()) {
				dirSet.add(retFd);
			}
			
			System.out.println("open fd: " + retFd);
			System.out.println("end open\n");
			return retFd;
		}

		public int close( int fd ) {
			System.out.println("CLOSE");
			System.out.println("fd: " + fd);
			if (fd < 0) {
				System.out.println("error: ebadf");
				return Errors.EBADF;
			}
			System.out.println(dirSet.contains(fd));
			if (dirSet.contains(fd)) {
				System.out.println("error: eisdir");
				return Errors.EISDIR;
			}
			if (!fdFileMap.containsKey(fd)) {
				System.out.println("error: enoent");
				return Errors.ENOENT;
			}
			RandomAccessFile closeFile = fdFileMap.get(fd);
			try {
				synchronized (this) {
					closeFile.close();
					fdFileMap.remove(fd, closeFile);
				}
			} catch (IOException e) {
				System.out.println("error: eperm");
				return Errors.EPERM;
			}
			System.out.println("end close\n");
			return 0;
		}

		public long write( int fd, byte[] buf ) {
			System.out.println("WRITE");
			System.out.println("fd: " + fd);
			System.out.println("buf: " + Arrays.toString(buf));
			if (buf == null) {
				System.out.println("error: einval");
				return Errors.EINVAL;
			}
			if (!fdFileMap.containsKey(fd)) {
				System.out.println("error: ebadf");
				return Errors.EBADF;
			}
			if (dirSet.contains(fd)) {
				System.out.println("error: eisdir");
				return Errors.EISDIR;
			}
			RandomAccessFile writeFile = fdFileMap.get(fd);
			
			try {
				synchronized(writeFile) {
					writeFile.write(buf);
				}
				
			} catch (IOException e) {
				System.out.println("error: eperm");
				return Errors.EBADF;
			}
			System.out.println("end write\n");
			return buf.length;
		}

		public long read( int fd, byte[] buf ) {
			System.out.println("READ");
			System.out.println("fd: " + fd);
			//System.out.println("buf: " + Arrays.toString(buf));
			if (buf == null) {
				System.out.println("error: einval");
				return Errors.EINVAL;
			}
			System.out.println(dirSet.contains(fd));
			if (dirSet.contains(fd)) {
				System.out.println("error: eisdir");
				return Errors.EISDIR;
			}
			if (!fdFileMap.containsKey(fd)) {
				System.out.println("error: badf");
				return Errors.EBADF;
			}
			
			RandomAccessFile readFile = fdFileMap.get(fd);
			long result = -1;
			try {
				result = readFile.read(buf);
			} catch (IOException e) {
				System.out.println("error: eperm");
				return Errors.EPERM;
			}
			//System.out.println("buf: " + Arrays.toString(buf));
			System.out.println("end read\n");
			if (result == -1) {
				return 0;
			}
			return result;
		}

		public long lseek( int fd, long pos, LseekOption o ) {
			System.out.println("LSEEK");
			System.out.println("fd: " + fd);
			System.out.println("pos: " + pos);
			if (!fdFileMap.containsKey(fd)) {
				System.out.println("error: ebadf");
				return Errors.EBADF;
			}
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
					System.out.println("error: einval");
					return Errors.EINVAL;
					
			}
			long newPos = start + pos;
			System.out.println("newPos: " + newPos);
			if (newPos < 0) {
				System.out.println("error: einval");
				return Errors.EINVAL;
			}
			try {
				synchronized(seekFile) {
					seekFile.seek(newPos);
				}
			} catch (IOException e) {
				System.out.println("error: eperm");
				return Errors.EPERM;
			}
			System.out.println("end lseek\n");
			return newPos;
		}

		public synchronized int unlink( String path ) {
			System.out.println("UNLINK");
			System.out.println("path: " + path);
			
			try {
				File unlinkFile = new File(path);
				if (!unlinkFile.exists()) {
					System.out.println("error: enoent");
					return Errors.ENOENT;
				}
				if (unlinkFile.isDirectory()) {
					System.out.println("error: isdir");
					return Errors.EISDIR;
				}
				unlinkFile.delete();
			} catch (Exception e) {
				System.out.println("error: eperm");
				return Errors.EPERM;
			}
			System.out.println("end unlink\n");
			return 0;
		}

		public void clientdone() {
			System.out.println("client done");
			for(RandomAccessFile file : fdFileMap.values()) {
				try {
					file.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			fdFileMap.clear();
			dirSet.clear();
			System.out.println("end client done");
			return;
		}

	}
	
	private static class FileHandlingFactory implements FileHandlingMaking {
		public FileHandling newclient() {
			return new FileHandler();
		}
	}

	public static void main(String[] args) throws IOException {
		System.out.println("Hello World");
		(new RPCreceiver(new FileHandlingFactory())).run();
	}
}

