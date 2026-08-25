package com.zeypher.lucycam;

interface IVcamBuffer {
    /** ParcelFileDescriptor of the Ashmem region holding the frame ring buffer. */
    android.os.ParcelFileDescriptor getBuffer();
    int getWidth();
    int getHeight();
    int getFormat(); // 1 = RGBA8888
}
