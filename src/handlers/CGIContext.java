package handlers;

import http.HttpRequest;

public class CGIContext {
    private final Process process;
    private final HttpRequest request;
    private final long startTime;
    private Thread readerThread;
    
    public CGIContext(Process process, HttpRequest request, long startTime) {
        this.process = process;
        this.request = request;
        this.startTime = startTime;
    }
    
    public Process getProcess() {
        return process;
    }
    
    public HttpRequest getRequest() {
        return request;
    }
    
    public long getStartTime() {
        return startTime;
    }

    public void setReaderThread(Thread t) {
        this.readerThread = t;
    }

    public Thread getReaderThread() {
        return readerThread;
    }
}