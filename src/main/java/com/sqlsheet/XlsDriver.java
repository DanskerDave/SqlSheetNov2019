/*
 * Copyright 2012 pcal.net
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sqlsheet;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.sqlsheet.stream.XlsStreamConnection;

/**
 * SqlSheet implementation of java.sql.Driver.
 *
 * @author <a href='http://www.pcal.net'>pcal</a>
 * @author <a href='http://code.google.com/p/sqlsheet'>sqlsheet</a>
 */
public class XlsDriver implements java.sql.Driver {

    static final String         READ_STREAMING  = "readStreaming";
    static final String         WRITE_STREAMING = "writeStreaming";
    static final String         HEADLINE        = "headLine";
    static final String         FIRST_COL       = "firstColumn";
    private static final String URL_SCHEME      = "jdbc:xls:";
    private static final Logger logger          = Logger.getLogger(XlsDriver.class.getName());

    static {
        try {
            DriverManager.registerDriver(new XlsDriver());
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Couldn't register " + XlsDriver.class.getName(), e);
        }
    }

    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    public Connection connect(String jdbcUrl, Properties info) throws SQLException {
        if (jdbcUrl == null)
            throw new IllegalArgumentException("Null url");
        if (!acceptsURL(jdbcUrl))
            return null; // why is this necessary?
        if (!jdbcUrl.toLowerCase().startsWith(URL_SCHEME)) {
            throw new IllegalArgumentException("URL is not " + URL_SCHEME + " (" + jdbcUrl + ")");
        }
        // strip any properties from end of URL and set them as additional properties
        String urlProperties;
        int questionIndex = jdbcUrl.indexOf('?');
        if (questionIndex >= 0) {
            info = new Properties(info);
            urlProperties = jdbcUrl.substring(questionIndex);
            String[] split = urlProperties.substring(1).split("&");
            for (String each : split) {
                String[] property = each.split("=");
                try {
                    if (property.length == 2) {
                        String key = URLDecoder.decode(property[0], "UTF-8");
                        String value = URLDecoder.decode(property[1], "UTF-8");
                        info.setProperty(key, value);
                    } else if (property.length == 1) {
                        String key = URLDecoder.decode(property[0], "UTF-8");
                        info.setProperty(key, Boolean.TRUE.toString());
                    } else {
                        throw new SQLException("Invalid property: " + each);
                    }
                } catch (UnsupportedEncodingException e) {
                    // we know UTF-8 is available
                }
            }
            jdbcUrl = jdbcUrl.substring(0, questionIndex);
        }
        try {
            URL workbookUrl = new URL(jdbcUrl.substring(URL_SCHEME.length()));
            // If streaming requested for read
            if (has(info, READ_STREAMING)) {
                return new XlsStreamConnection(workbookUrl);
            } else if (workbookUrl.getProtocol().equalsIgnoreCase("file")) {
                // If streaming requested for write
                if (has(info, WRITE_STREAMING)) {
                    return new XlsConnection(getOrCreateXlsxStream(workbookUrl), new File(workbookUrl.getPath()), info);
                }
                return new XlsConnection(getOrCreateWorkbook(workbookUrl), new File(workbookUrl.getPath()), info);
            } else {
                // If plain url provided
                return new XlsConnection(WorkbookFactory.create(workbookUrl.openStream()), info);
            }
        } catch (Exception e) {
            SQLException sqe = new SQLException(e.getMessage());
            sqe.initCause(e);
            throw sqe;
        }
    }

    boolean has(Properties info, String key) {
        Object value = info.get(key);
        if (value == null) {
            return false;
        }
        return value.equals(Boolean.TRUE.toString());
    }

    private SXSSFWorkbook getOrCreateXlsxStream(URL workbookUrl) throws IOException, InvalidFormatException {
        if (workbookUrl.getProtocol().equalsIgnoreCase("file")) {
            File source = new File(workbookUrl.getPath());
            if (source.exists() || (source.length() != 0)) {
                logger.warning("File " + source.getPath() + " is not empty, and will parsed to memory!");
            } else {
                Workbook workbook = new XSSFWorkbook();
                flushWorkbook(workbook, source);
            }
        }
        return new SXSSFWorkbook(new XSSFWorkbook(workbookUrl.openStream()), 1000, false);
    }

    private Workbook getOrCreateWorkbook(URL workbookUrl) throws IOException, InvalidFormatException {
        if (workbookUrl.getProtocol().equalsIgnoreCase("file")) {
            File file = new File(workbookUrl.getPath());
            if (!file.exists() || (file.length() == 0)) {
                Workbook workbook;
                if (file.getPath().toLowerCase().endsWith("x")) {
                    workbook = new XSSFWorkbook();
                } else {
                    workbook = new HSSFWorkbook();
                }
                flushWorkbook(workbook, file);
            }
        }
        return WorkbookFactory.create(workbookUrl.openStream());
    }

    private void flushWorkbook(Workbook workbook, File file) throws IOException {
        FileOutputStream fileOut = null;
        try {
            fileOut = new FileOutputStream(file);
            workbook.write(fileOut);
            fileOut.flush();
        } finally {
            if (fileOut != null) {
                try {
                    fileOut.close();
                } catch (IOException e) {
                    logger.log(Level.WARNING, e.getMessage(), e);
                }
            }
        }
    }

    public boolean acceptsURL(String url) throws SQLException {
        return url != null && url.trim().toLowerCase().startsWith(URL_SCHEME);
    }

    public boolean jdbcCompliant() { // LOLZ!
        return false;
    }

    public int getMajorVersion() {
        return 1;
    }

    public int getMinorVersion() {
        return 0;
    }

    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

}
