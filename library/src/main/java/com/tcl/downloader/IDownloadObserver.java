package com.tcl.downloader;

/**
 * Created by wangdan on 16/6/15.
 */
public interface IDownloadObserver {

    String downloadURI();

    void onDownloadPrepare();

    void onDownloadChanged(DownloadController.DownloadStatus status);

}