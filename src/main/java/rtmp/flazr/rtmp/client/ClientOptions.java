/*
 * Flazr <http://flazr.com> Copyright (C) 2009  Peter Thomas.
 *
 * This file is part of Flazr.
 *
 * Flazr is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Flazr is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Flazr.  If not, see <http://www.gnu.org/licenses/>.
 */

package rtmp.flazr.rtmp.client;

import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rtmp.flazr.rtmp.RtmpHandshake;
import rtmp.flazr.rtmp.RtmpReader;
import rtmp.flazr.rtmp.RtmpWriter;
import rtmp.flazr.rtmp.server.ServerStream;
import rtmp.flazr.util.Utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClientOptions {

    private static final Logger logger = LoggerFactory.getLogger(ClientOptions.class);

    private ServerStream.PublishType publishType;
    private String host = "localhost";
    private int port = 1935;
    private String appName = "vod";
    private String streamName;
    private String fileToPublish;
    private RtmpReader readerToPublish;
    private RtmpWriter writerToSave;
    private String saveAs;    
    private boolean rtmpe;
    private Map<String, Object> params;
    private Object[] args;
    private byte[] clientVersionToUse;
    private int start = -2;
    private int length = -1;
    private int buffer = 100;
    private byte[] swfHash;
    private int swfSize;
    private int load = 1;
    private int loop = 1;
    private int threads = 10;
    private List<ClientOptions> clientOptionsList;

    public ClientOptions() {}

    public ClientOptions(String host, String appName, String streamName, String saveAs) {
        this(host, 1935, appName, streamName, saveAs, false, null);
    }

    public ClientOptions(String host, int port, String appName, String streamName, String saveAs,
            boolean rtmpe, String swfFile) {
        this.host = host;
        this.port = port;
        this.appName = appName;
        this.streamName = streamName;
        this.saveAs = saveAs;
        this.rtmpe = rtmpe;        
        if(swfFile != null) {
            initSwfVerification(swfFile);
        }
    }

    private static final Pattern URL_PATTERN = Pattern.compile(
          "(rtmp.?)://" // 1) protocol
        + "([^/:]+)(:[0-9]+)?/" // 2) host 3) port
        + "([^/]+)/" // 4) app
        + "(.*)" // 5) play
    );

    public ClientOptions(String url, String saveAs) {
        parseUrl(url);
        this.saveAs = saveAs;
    }

    public void parseUrl(String url) {
        Matcher matcher = URL_PATTERN.matcher(url);
        if (!matcher.matches()) {
            throw new RuntimeException("invalid url: " + url);
        }

        logger.debug("parsing url: {}", url);
        String protocol = matcher.group(1);
        logger.debug("protocol = '{}'",  protocol);
        host = matcher.group(2);
        logger.debug("host = '{}'", host);

        String portString = matcher.group(3);
        if (portString == null) {
            logger.debug("port is null in url, will use default 1935");
        } else {
            portString = portString.substring(1); // skip the ':'
            logger.debug("port = '{}'", portString);
        }

        port = portString == null ? 1935 : Integer.parseInt(portString);
        appName = matcher.group(4);
        logger.debug("app = '{}'",  appName);
        streamName = matcher.group(5);
        logger.debug("playName = '{}'", streamName);

        rtmpe = protocol.equalsIgnoreCase("rtmpe");
        if(rtmpe) {
            logger.debug("rtmpe requested, will use encryption");
        }        
    }
    
    public void publishLive() {
        publishType = ServerStream.PublishType.LIVE;        
    }
    
    public void publishRecord() {
        publishType = ServerStream.PublishType.RECORD;        
    }
    
    public void publishAppend() {
        publishType = ServerStream.PublishType.APPEND;        
    }

    //==========================================================================
    protected static Options getCliOptions() {
        final Options options = new Options();

        options.addOption(new Option("rtmpe", "use RTMPE (encryption)"));
        options.addOption(new Option("live", "publish local file to server in 'live' mode"));
        options.addOption(new Option("record", "publish local file to server in 'record' mode"));
        options.addOption(new Option("append", "publish local file to server in 'append' mode"));
        options.addOption(new Option("file", "spawn connections listed in file (load testing)"));

        options.addOption(Option.builder("host").desc("host name").hasArg().argName("host").build());
        options.addOption(Option.builder("port").desc("port number").hasArg().argName("port").build());
        options.addOption(Option.builder("app").desc("app name").hasArg().argName("app").build());
        options.addOption(Option.builder("start").desc("start position (milliseconds)").hasArg().argName("start").build());
        options.addOption(Option.builder("length").desc("length (milliseconds)").hasArg().argName("length").build());
        options.addOption(Option.builder("buffer").desc("buffer duration (milliseconds)").hasArg().argName("buffer").build());
        //options.addOption(Option.builder("property=value").desc("add / override connection param").numberOfArgs(2).valueSeparator().hasArgs().argName("D").build());
        options.addOption(Option.builder("swf").desc("path to (decompressed) SWF for verification").hasArg().argName("swf").build());
        options.addOption(Option.builder("version").desc("client version to use in RTMP handshake (hex)").hasArg().argName("version").build());
        options.addOption(Option.builder("load").desc("no. of client connections (load testing)").hasArg().argName("load").build());
        options.addOption(Option.builder("loop").desc("for publish mode, loop count").hasArg().argName("loop").build());
        options.addOption(Option.builder("threads").desc("for load testing (load) mode, thread pool size").hasArg().argName("threads").build());

        return options;
    }

    public boolean parseCli(final String[] args) {
        CommandLineParser parser = new DefaultParser();
        CommandLine line;
        final Options options = getCliOptions();
        try {
            line = parser.parse(options, args);

            if(line.hasOption("host")) {
                host = line.getOptionValue("host");
            }
            if(line.hasOption("port")) {
                port = Integer.parseInt(line.getOptionValue("port"));
            }  
            if(line.hasOption("app")) {
                appName = line.getOptionValue("app");
            }
            if(line.hasOption("start")) {
                start = Integer.parseInt(line.getOptionValue("start"));
            }
            if(line.hasOption("length")) {
                length = Integer.parseInt(line.getOptionValue("length"));
            }
            if(line.hasOption("buffer")) {
                buffer = Integer.parseInt(line.getOptionValue("buffer"));
            }
            if(line.hasOption("rtmpe")) {
                rtmpe = true;
            }
            if(line.hasOption("live")) {
                publishLive();
            }
            if(line.hasOption("record")) {
                publishRecord();
            }
            if(line.hasOption("append")) {
                publishAppend();
            }
            if(line.hasOption("version")) {
                clientVersionToUse = Utils.fromHex(line.getOptionValue("version"));
                if(clientVersionToUse.length != 4) {
                    throw new RuntimeException("client version to use has to be 4 bytes long");
                }
            }
            /*if(line.hasOption("D")) { // need to change D to integers, extra args for 'play' command
                params = new HashMap(line.getOptionProperties("D"));
            }*/
            if(line.hasOption("load")) {
                load = Integer.parseInt(line.getOptionValue("load"));
                if(publishType != null && load > 1) {
                    throw new RuntimeException("cannot publish in load testing mode");
                }
            }
            if(line.hasOption("threads")) {
                threads = Integer.parseInt(line.getOptionValue("threads"));
            }
            if(line.hasOption("loop")) {
                loop = Integer.parseInt(line.getOptionValue("loop"));
                if(publishType == null && loop > 1) {
                    throw new RuntimeException("cannot loop when not in publish mode");
                }
            }
        } catch(Exception e) {
            logger.warn("parsing failed: {}", e.getMessage());
            return false;
        }

        String[] actualArgs = line.getArgs();
        if(line.hasOption("file")) {
            String fileName = actualArgs[0];
            File file = new File(fileName);
            if(!file.exists()) {
                throw new RuntimeException("file does not exist: '" + fileName + "'");
            }
            logger.info("parsing file: {}", file);
            try {
                FileInputStream fis = new FileInputStream(file);
                BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
                int i = 0;
                String s;
                clientOptionsList = new ArrayList<>();
                while ((s = reader.readLine()) != null) {
                    i++;
                    logger.debug("parsing line {}: {}", i, s);
                    String[] tempArgs = s.split("\\s");
                    ClientOptions tempOptions = new ClientOptions();
                    if(!tempOptions.parseCli(tempArgs)) {
                        throw new RuntimeException("aborting, parsing failed at line " + i);
                    }
                    clientOptionsList.add(tempOptions);
                }
                reader.close();
                fis.close();
            } catch(Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            Matcher matcher = URL_PATTERN.matcher(actualArgs[0]);
            if (matcher.matches()) {
                parseUrl(actualArgs[0]);
            } else {
                streamName = actualArgs[0];
            }
        }

        if(publishType != null) {
            if(actualArgs.length < 2) {
                logger.warn("fileToPublish is required for publish mode ({})", actualArgs);
                return false;
            }
            fileToPublish = actualArgs[1];
        } else if(actualArgs.length > 1) {
            saveAs = actualArgs[1];
        }

        logger.info("options: {}", this);
        return true;
    }
    //==========================================================================

    public int getLoad() {
        return load;
    }

    public void setLoad(int load) {
        this.load = load;
    }

    public int getLoop() {
        return loop;
    }

    public void setLoop(int loop) {
        this.loop = loop;
    }

    public String getFileToPublish() {
        return fileToPublish;
    }

    public void setFileToPublish(String fileName) {
        this.fileToPublish = fileName;
    }

    public RtmpReader getReaderToPublish() {
        return readerToPublish;
    }

    public void setReaderToPublish(RtmpReader readerToPublish) {
        this.readerToPublish = readerToPublish;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getTcUrl() {
        return (rtmpe ? "rtmpe://" : "rtmp://") + host + ":" + port + "/" + appName;
    }

    public void setArgs(Object ... args) {
        this.args = args;
    }

    public Object[] getArgs() {
        return args;
    }

    public void setClientVersionToUse(byte[] clientVersionToUse) {
        this.clientVersionToUse = clientVersionToUse;
    }

    public byte[] getClientVersionToUse() {
        return clientVersionToUse;
    }

    public void initSwfVerification(String pathToLocalSwfFile) {
        initSwfVerification(new File(pathToLocalSwfFile));
    }

    public void initSwfVerification(File localSwfFile) {
        logger.info("initializing swf verification data for: {}", localSwfFile.getAbsolutePath());
        byte[] bytes = Utils.readAsByteArray(localSwfFile);
        byte[] hash = Utils.sha256(bytes, RtmpHandshake.CLIENT_CONST);
        swfSize = bytes.length;
        swfHash = hash;
        logger.info("swf verification initialized - size: {}, hash: {}", swfSize, Utils.toHex(swfHash));
    }
    
    public void putParam(String key, Object value) {
        if(params == null) {
            params = new LinkedHashMap<>();
        }
        params.put(key, value);
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public ServerStream.PublishType getPublishType() {
        return publishType;
    }

    public void setPublishType(ServerStream.PublishType publishType) {
        this.publishType = publishType;
    }

    public String getStreamName() {
        return streamName;
    }

    public void setStreamName(String streamName) {
        this.streamName = streamName;
    }

    public int getStart() {
        return start;
    }

    public void setStart(int start) {
        this.start = start;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public int getBuffer() {
        return buffer;
    }

    public void setBuffer(int buffer) {
        this.buffer = buffer;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getSaveAs() {
        return saveAs;
    }

    public void setSaveAs(String saveAs) {
        this.saveAs = saveAs;
    }

    public boolean isRtmpe() {
        return rtmpe;
    }

    public byte[] getSwfHash() {
        return swfHash;
    }

    public void setSwfHash(byte[] swfHash) {
        this.swfHash = swfHash;
    }

    public int getSwfSize() {
        return swfSize;
    }

    public void setSwfSize(int swfSize) {
        this.swfSize = swfSize;
    }

    public int getThreads() {
        return threads;
    }

    public void setThreads(int threads) {
        this.threads = threads;
    }

    public RtmpWriter getWriterToSave() {
        return writerToSave;
    }

    public void setWriterToSave(RtmpWriter writerToSave) {
        this.writerToSave = writerToSave;
    }

    public List<ClientOptions> getClientOptionsList() {
        return clientOptionsList;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[host: '").append(host);
        sb.append("' port: ").append(port);
        sb.append(" appName: '").append(appName);
        sb.append("' streamName: '").append(streamName);
        sb.append("' saveAs: '").append(saveAs);
        sb.append("' rtmpe: ").append(rtmpe);
        sb.append(" publish: ").append(publishType);
        if(clientVersionToUse != null) {
            sb.append(" clientVersionToUse: '").append(Utils.toHex(clientVersionToUse)).append('\'');
        }
        sb.append(" start: ").append(start);
        sb.append(" length: ").append(length);
        sb.append(" buffer: ").append(buffer);
        sb.append(" params: ").append(params);
        sb.append(" args: ").append(Arrays.toString(args));
        if(swfHash != null) {
            sb.append(" swfHash: '").append(Utils.toHex(swfHash));
            sb.append("' swfSize: ").append(swfSize).append('\'');
        }
        sb.append(" load: ").append(load);
        sb.append(" loop: ").append(loop);
        sb.append(" threads: ").append(threads);
        sb.append(']');
        return sb.toString();
    }
    
}
