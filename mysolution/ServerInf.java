/**
 * ServerInf.java
 * @author Jiayue Mao
 * Andrew ID: jiayuem
 */

import java.rmi.*;

public interface ServerInf extends Remote {

    // sendModifiedTime: send the last modified time of a file with specific path
    public long sendModifiedTime(String path) throws RemoteException;

    // openOnServer: open a file with specific file in server storage
    public int openOnServer(String path, FileHandling.OpenOption o) throws RemoteException;

    // readOnServer: read a file with specific path in server storage
    public Chunk readOnServer(String path, int offset, int readSize, FileHandling.OpenOption o, long cacheSize) throws RemoteException;

    // writeOnServer: write to a file in the server storage
    public int writeOnServer(String path, Chunk chunk, int offset) throws RemoteException;

    // unlinkOnServer: unlink a file in server storage
    public int unlinkOnServer(String path) throws RemoteException;
    
}