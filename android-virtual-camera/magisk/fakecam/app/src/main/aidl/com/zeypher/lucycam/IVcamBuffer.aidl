package com.zeypher.lucycam;

interface IVcamBuffer {
    android.os.ParcelFileDescriptor getBuffer();
    int getWidth();
    int getHeight();
    int getFormat();
}
