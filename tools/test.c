#include <dlfcn.h>
#include <dirent.h>
#include <stdio.h>
 
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdarg.h>

#include <stdlib.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <string.h>
#include <err.h>
#include <errno.h>

int main(int argc, char**argv) {
    int fd = open("tempdir/", O_RDONLY);
    printf("open fd: %d\n", fd);
    printf("errno: %d\n", errno);
    char buf[10];
    read(fd, buf, 10);
    printf("read errno: %d\n", errno);
    close(fd);
    printf("close fd: %d\n", errno);
    return 0;

}
