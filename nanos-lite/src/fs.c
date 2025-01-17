#include <fs.h>

#define ARRLEN(arr) (sizeof(arr)/sizeof(arr[0]))

typedef size_t (*ReadFn) (void *buf, size_t offset, size_t len);
typedef size_t (*WriteFn) (const void *buf, size_t offset, size_t len);

typedef struct {
  char *name;
  size_t size;
  size_t disk_offset;
  ReadFn read;
  WriteFn write;
  bool not_seekable;
  size_t open_offset;
} Finfo;

enum { FD_STDIN, FD_STDOUT, FD_STDERR, FD_EVENTS, FD_DISPINFO, FD_FB };

enum { FD_SEEKABLE, FD_NOT_SEEKABLE };

size_t invalid_read(void *buf, size_t offset, size_t len) {
  panic("should not reach here");
  return 0;
}

size_t invalid_write(const void *buf, size_t offset, size_t len) {
  panic("should not reach here");
  return 0;
}

size_t dispinfo_read(void *buf, size_t offset, size_t len);

size_t fb_write(const void *buf, size_t offset, size_t len);

size_t events_read(void *buf, size_t offset, size_t len);

size_t serial_write(const void *buf, size_t offset, size_t len);

size_t ramdisk_read(void *buf, size_t offset, size_t len);

size_t ramdisk_write(const void *buf, size_t offset, size_t len);


/* This is the information about all files in disk. */
static Finfo file_table[] __attribute__((used)) = {
  [FD_STDIN]  = {"stdin", 0, 0, invalid_read, invalid_write, FD_NOT_SEEKABLE},
  [FD_STDOUT] = {"stdout", 0, 0, invalid_read, serial_write, FD_NOT_SEEKABLE},
  [FD_STDERR] = {"stderr", 0, 0, invalid_read, serial_write, FD_NOT_SEEKABLE},
  [FD_EVENTS] = {"/dev/events", 0, 0, events_read, invalid_write, FD_NOT_SEEKABLE},
  [FD_DISPINFO]  = {"/proc/dispinfo", 0, 0, dispinfo_read, invalid_write, FD_NOT_SEEKABLE},
  [FD_FB]     = {"/dev/fb", 0, 0, invalid_read, fb_write, FD_SEEKABLE},
#include "files.h"
};

void init_fs() {
  // AM_GPU_CONFIG_T config;
  // ioe_read(AM_GPU_CONFIG, &config);
  // file_table[FD_FB].size = config.height * config.width;
}

int fs_open(const char *pathname)
{
  for (int i = 0; i < ARRLEN(file_table); ++i) {
    if (strcmp(pathname, file_table[i].name) == 0){
      file_table[i].open_offset = 0;
      return i;
    }
  }
  return -1;
}

static bool is_seekable(Finfo *file)
{
  return !file->not_seekable;
}

static ReadFn get_read_fn(Finfo *file)
{
  return file->read == NULL ? ramdisk_read : file->read;
}

static WriteFn get_write_fn(Finfo *file)
{
  return file->write == NULL ? ramdisk_write : file->write;
}

static size_t get_real_count(Finfo *file, size_t count){  
  // by default real_count is whatever user give
  size_t real_count = count;
  // if it is a seekable file, truncate to end of file
  if(is_seekable(file)){
    size_t len_to_end = file->size - file->open_offset;
    real_count = (len_to_end < count) ? len_to_end : count;
  }
  return real_count;
}

static size_t get_offset_advance(Finfo *file, size_t retval){
  // only seekable device need to move open_offset;
  return is_seekable(file) ? retval : 0;
}

size_t fs_read(int fd, void *buf, size_t count){
  assert(fd >= 0 && fd < ARRLEN(file_table));
  Finfo *file = &file_table[fd];    
  
  ReadFn read_fn = get_read_fn(file);
  size_t real_count = get_real_count(file, count);
  size_t retval =
      read_fn(buf, file->disk_offset + file->open_offset, real_count);

  file->open_offset += get_offset_advance(file, retval);
  return retval;
}

size_t fs_write(int fd, const void *buf, size_t count) {
  assert(fd >= 0 && fd < ARRLEN(file_table));
  Finfo *file = &file_table[fd];    
  
  WriteFn write_fn = get_write_fn(file);
  size_t real_count = get_real_count(file, count);
  size_t retval =
      write_fn(buf, file->disk_offset + file->open_offset, real_count);

  file->open_offset += get_offset_advance(file, retval);
  return retval;
}

size_t fs_lseek(int fd, size_t offset, int whence) {
  Finfo *file = &file_table[fd];
  if (!is_seekable(file)){
    return -1;
  }
  size_t new_offset = -1;
  switch (whence)
  {
  case SEEK_SET:
    new_offset = offset;
    break;
  case SEEK_CUR:
    new_offset = file->open_offset + offset;
    break;
  
  case SEEK_END:
    new_offset = file->size;
    break;
  
  default:
    panic("whence = %d is not supported", whence);
    break;
  }
  file->open_offset = new_offset;
  return new_offset;
}

int fs_close(int fd) {
  return 0;
}

const char* fs_getfilename(int fd)
{
  assert(fd >= 0 && fd < ARRLEN(file_table));
  return file_table[fd].name;
}