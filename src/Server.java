/**
 * Server.java
 * @author Jiayue Mao
 * Andrew ID: jiayuem
 */

import java.io.*;
import java.net.MalformedURLException;
import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.rmi.Naming;
import java.rmi.RemoteException;

public class Server extends UnicastRemoteObject implements ServerInf{

    private static final long serialVersionUID = 661385625476124614L;

    /* the path string of server local storage directory */
    public String serverPath;

    /**
     * Server constructor
     * @param path          the path of server local storage directory
     * @throws RemoteException
     */
    public Server(String path) throws RemoteException{
        this.serverPath = path;
    }

    /**
     * createServerPath: create absolute path with server storage directory
     * @param path          original path
     * @return absolute path in server storage
     */
    private String createServerPath(String path) {
        StringBuilder sb = new StringBuilder(serverPath);
        sb.append("/");
        sb.append(path);
        return sb.toString();
    }

    /**
     * validatePath: determine whether the file path is valid or not.
     * @param realPath      absolute path of file in server storage
     * @return true, if valid; false, otherwise.
     */
    public boolean validatePath(String realPath){
        File file = new File(realPath);
        File serverFile = new File(serverPath);
        try {
            if (!file.getCanonicalPath().startsWith(serverFile.getCanonicalPath()))
                return false;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return true;
    }

	/**
	 * sendModifiedTime: send the last modified time of a file with specific path
	 * @param  path            original path of the file
	 * @return                 last modified time
	 * @throws RemoteException
	 */
    @Override
    public long sendModifiedTime(String path) throws RemoteException{
        String realPath = createServerPath(path);
        if (!validatePath(realPath)) return FileHandling.Errors.EPERM;
        File file = new File(realPath);
        return file.lastModified();
    }

	/**
	 * openOnServer: open a file with specific file in server storage
	 * @param  path            original path
	 * @param  o               open option
	 * @return                 the length of the opened file
	 * @throws RemoteException
	 */
    @Override
    public int openOnServer(String path, FileHandling.OpenOption o) throws RemoteException {  
        String realPath = createServerPath(path);
        File file = new File(realPath);
        String mode = "";
        switch (o) {
            case READ:
                if (!file.exists()) {
                    return FileHandling.Errors.ENOENT;
                }
                mode = "r";
                break;
            case WRITE:
                if (!file.exists()) {
                    return FileHandling.Errors.ENOENT;
                }
                if (file.isDirectory()) {
                    return FileHandling.Errors.EISDIR;
                }
                mode = "rw";
                break;
            case CREATE:
                if (!file.exists()) {
                    try {
                        file.createNewFile();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                mode = "rw";
                break;
            case CREATE_NEW:
                if (file.exists()) {
                    return FileHandling.Errors.EEXIST;
                } else {
                    try {
                        file.createNewFile();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                mode = "rw";
                break;		
            default:
                return FileHandling.Errors.EINVAL;
        }

        int fileLen = (int) file.length();
        if (file.isDirectory()) return Integer.MIN_VALUE;
        return fileLen;
    }

	/**
	 * readOnServer: read a file with specific path in server storage
     * combine open with read: if the file is read from the start, 
     * first open the file, then read.
	 * @param  path            original path
	 * @param  offset          byte offset in the file
     * @param  readSize        the length to read
     * @param  o               open option
     * @param  cacheSize       cache size limit 
     * (deal with the situation if the file length is larger than the limit)
	 * @return                 Chunk object
	 * @throws RemoteException
	 */
    @Override
    public Chunk readOnServer(String path, int offset, int readSize, FileHandling.OpenOption o, long cacheSize) throws RemoteException{
        // if from start, open first
        if (offset == 0) {
            int openResult = openOnServer(path, o);
            if (openResult <= 0) {
                Chunk chunk = new Chunk(openResult);
                return chunk;
            }
            if (openResult > cacheSize) {
                Chunk chunk = new Chunk(0);
                chunk.size = FileHandling.Errors.ENOMEM;
                return chunk;
            }
        }
        String realPath = createServerPath(path);
        File file = new File(realPath);
        if (!file.exists()) {
            Chunk chunk = new Chunk(1);
            chunk.size = FileHandling.Errors.EINVAL;
            return chunk;
        }
        if (file.isDirectory()) {
            Chunk chunk = new Chunk(1);
            chunk.size = FileHandling.Errors.EISDIR;
            return chunk;
        }
        boolean remain = false;
        int fileLen = (int) file.length();
        int readLen = fileLen - offset;
        int chunkSize = Math.min(readLen, readSize);
        // if to the end, return
        if (readLen == 0) {
            Chunk chunk = new Chunk(0);
            chunk.remain = false;
            return chunk;
        }
        // if one chunk can read to the end of the file, remain is false
        if (readLen <= readSize) {
            remain = false;
        } else {
            remain = true;
        }

        RandomAccessFile raFile = null;
        try {
            raFile = new RandomAccessFile(realPath, "rw");
        } catch (Exception e) {
            e.printStackTrace();;
        }

        Chunk chunk = new Chunk(chunkSize);
        long result = -1;
        try {
            raFile.seek(offset);
            readLen = raFile.read(chunk.content);
        } catch (IOException e) {
            chunk.size = FileHandling.Errors.ENOENT;
            return chunk;
        }
        if (readLen == -1) {
            chunk.size = chunk.content.length;
            chunk.remain = false;
        }
        else {
            chunk.size = chunkSize;
            chunk.remain = remain;
        }
        return chunk;        
    }

	/**
	 * writeOnServer: write to a file in the server storage
	 * @param  path            original path
     * @param  chunk           chunk received from proxy whose content is to be wrote 
	 * @param  offset          byte offset 
	 * @return                 chunk containg data/error
	 * @throws RemoteException
	 */
    @Override
    public int writeOnServer(String path, Chunk chunk, int offset) throws RemoteException{
        if (chunk == null) {
            return FileHandling.Errors.EINVAL;
        }
        String realPath = createServerPath(path);
        File file = new File(realPath);
        if (!file.exists()) return FileHandling.Errors.EBADF;
        if (file.isDirectory()) return FileHandling.Errors.EISDIR;
        RandomAccessFile raFile = null;
        try {
            raFile = new RandomAccessFile(file, "rw");
        } catch (FileNotFoundException e1) {
            e1.printStackTrace();
        }
		
        if (raFile != null) {
            try {
                // first seek to the file offset, then write from the offset
                raFile.seek(offset);
                raFile.write(chunk.content, 0, chunk.size);
            } catch (IOException e) {
                try {
                    raFile.close();
                } catch (Exception e1) {
                    e1.printStackTrace();
                }
                return FileHandling.Errors.EINVAL;
            }
        }
		
        try {
            raFile.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return chunk.size;

    }

	/**
	 * unlinkOnServer: unlink a file in server storage
	 * @param  path            original path
	 * @return                 0 if succeed, < 0 if failed
	 * @throws RemoteException
	 */
    @Override
    public int unlinkOnServer(String path) throws RemoteException{
        String realPath = createServerPath(path);
        File file = new File(realPath);
        if (!file.exists()) {
            return FileHandling.Errors.ENOENT;
        }
        if (file.isDirectory()) {
            return FileHandling.Errors.EISDIR;
        }
        if (!file.delete()) {
            return FileHandling.Errors.EBUSY;
        }
        return 0;
    }

    public static void main (String[] args) {
        if (args.length < 2) {
            return;
        }

        int port = Integer.parseInt(args[0]);
        try {
            LocateRegistry.createRegistry(port);
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        Server server = null;
        try {
            server = new Server(args[1]);
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        String url = "//127.0.0.1:" + port + "/Server";
        try {
            Naming.rebind(url, server);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}