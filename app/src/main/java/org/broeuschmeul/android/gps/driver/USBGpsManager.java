/*
 * Copyright (C) 2016, 2017 Oliver Bell
 * Copyright (C) 2010, 2011, 2012 Herbert von Broeuschmeul
 * Copyright (C) 2010, 2011, 2012 BluetoothGPS4Droid Project
 * Copyright (C) 2011, 2012 UsbGPS4Droid Project
 * 
 * This file is part of UsbGPS4Droid.
 *
 * UsbGPS4Droid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * UsbGPS4Droid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 *  You should have received a copy of the GNU General Public License
 *  along with UsbGPS4Droid. If not, see <http://www.gnu.org/licenses/>.
 */

package org.broeuschmeul.android.gps.driver;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.broeuschmeul.android.gps.nmea.util.NmeaParser;
import org.broeuschmeul.android.gps.BuildConfig;
import org.broeuschmeul.android.gps.R;
import org.broeuschmeul.android.gps.USBGpsApplication;
import org.broeuschmeul.android.gps.ui.GpsInfoActivity;
import org.broeuschmeul.android.gps.util.SuperuserManager;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import androidx.preference.PreferenceManager;
import android.app.AppOpsManager;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import android.provider.Settings;
import android.util.Log;


/**
 * This class is used to establish and manage the connection with the bluetooth GPS.
 *
 * @author Herbert von Broeuschmeul
 */
public class USBGpsManager {

    /**
     * Tag used for log messages
     */
    private static final String LOG_TAG = USBGpsManager.class.getSimpleName();

    // Has more connections logs
    private final boolean
            debug = false;

    private UsbManager usbManager = null;
    private static final String ACTION_USB_PERMISSION =
            "org.broeuschmeul.android.gps.driver.USBGpsManager.USB_PERMISSION";

    /**
     * Used to listen for nmea updates from UsbGpsManager
     */
    public interface NmeaListener {
        void onNmeaReceived(long timestamp, String nmea);
    }

    private final BroadcastReceiver permissionAndDetachReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                synchronized (this) {
                    if (connectedGps != null && enabled) {
                        connectedGps.close();
                    }
                }
            }
        }
    };

    /**
     * A utility class used to manage the communication with the bluetooth GPS whn the connection has been established.
     * It is used to read NMEA data from the GPS or to send SIRF III binary commands or SIRF III NMEA commands to the GPS.
     * You should run the main read loop in one thread and send the commands in a separate one.
     *
     * @author Herbert von Broeuschmeul
     */
    private class ConnectedGps extends Thread {
        private final UsbInterface intf;
        private UsbEndpoint endpointIn;
        private UsbEndpoint endpointOut;
        private final UsbDeviceConnection connection;
        private boolean closed = false;
        /**
         * GPS InputStream from which we read data.
         */
        private final InputStream in;
        /**
         * GPS output stream to which we send data (SIRF III binary commands).
         */
        private final OutputStream out;
        /**
         * GPS output stream to which we send data (SIRF III NMEA commands).
         */
        private final PrintStream out2;
        /**
         * A boolean which indicates if the GPS is ready to receive data.
         * In fact we consider that the GPS is ready when it begins to sends data...
         */
        private boolean ready = false;

        // ... существующие поля ...
        private boolean dtrState = true; // Начальное желаемое состояние DTR
        private boolean rtsState = true; // Начальное желаемое состояние RTS

        // Константы для CH340/CH341
        private static final int REQTYPE_HOST_TO_DEVICE_VENDOR_DEVICE = 0x40; // UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_OUT | UsbConstants.USB_RECIP_DEVICE

        private static final int CH34X_REQ_SERIAL_INIT = 0xA1; // Инициализация чипа
        private static final int CH34X_REQ_MODEM_CTRL = 0xA4;  // Управление DTR/RTS
        private static final int CH34X_REQ_WRITE_REG = 0x9A;   // Запись в регистры (для baud, LCR)

        // Биты для команды CH34X_REQ_MODEM_CTRL (0xA4). Значение для value: ~((DTR_BIT << 5) | (RTS_BIT << 6))
        private static final int CH34X_LCTRL_DTR_ACTIVE_BIT = 1 << 5; // 0x20
        private static final int CH34X_LCTRL_RTS_ACTIVE_BIT = 1 << 6; // 0x40

        // Константы для установки параметров (пример для CH340, 9600 8N1)
        private static final int CH34X_BAUD_VALUE_9600_FOR_0x1312 = 0xb282; // Для команды 0x9A, value=0x1312, index=0xb282 (для CH340)
        // Другие варианты для 9600 на CH340: value=0x2518, index=0x00cf

        private static final int CH34X_LCR_REGISTER_ADDRESS_CH340_PLUS = 0x2727; // Адрес регистра для LCR
        private static final byte CH34X_LCR_VALUE_8N1 = 0x03; // 8 data bits, No parity, 1 stop bit
        private static final int CH34X_LCR_INDEX_CH340_PLUS = 0x0000; // Индекс для команды LCR с данными

        private boolean isCh34xDevice = false; // Флаг, что это CH34x

        public ConnectedGps(UsbDevice device) {
            this(device, defaultDeviceSpeed);
        }

        // Внутренний метод для применения текущего состояния DTR/RTS
        private void applyDtrRtsState(int timeoutMs) throws IOException {
            if (!isCh34xDevice || closed || connection == null) {
                if (!isCh34xDevice) debugLog("Not a CH34x device, DTR/RTS control via CH34X_REQ_MODEM_CTRL skipped.");
                else debugLog("applyDtrRtsState: Connection not ready or closed.");
                return;
            }

            int modemLinesValue = 0;
            if (dtrState) {
                modemLinesValue |= CH34X_LCTRL_DTR_ACTIVE_BIT;
            }
            if (rtsState) {
                modemLinesValue |= CH34X_LCTRL_RTS_ACTIVE_BIT;
            }

            // Для CH34x значение для controlTransfer инвертируется
            int ch34xCtrlValue = (~modemLinesValue) & 0xFFFF; // Маска для 16-битного положительного значения

            debugLog(String.format("CH34x: Applying DTR=%b, RTS=%b. Control value=0x%X",
                    dtrState, rtsState, ch34xCtrlValue));

            int ret = connection.controlTransfer(ConnectedGps.REQTYPE_HOST_TO_DEVICE_VENDOR_DEVICE, CH34X_REQ_MODEM_CTRL,
                    ch34xCtrlValue, 0x0000, null, 0, timeoutMs);

            if (ret < 0) {
                // Не будем бросать IOException, чтобы не прерывать все, но залогируем
                Log.e(LOG_TAG, "CH34x: Failed to set DTR/RTS lines, error: " + ret);
            } else {
                debugLog("CH34x: DTR/RTS lines updated successfully.");
            }
        }

        // Публичные методы для управления DTR/RTS извне (например, из USBGpsManager)
        public void setDTR(boolean dtr) {
            if (this.dtrState == dtr && isCh34xDevice) return; // Не меняем, если состояние то же и это CH34x
            this.dtrState = dtr;
            if (isCh34xDevice && !closed && connection != null) {
                try {
                    applyDtrRtsState(1000); // Используем стандартный таймаут
                } catch (IOException e) {
                    Log.e(LOG_TAG, "IOException while setting DTR for CH34x", e);
                }
            } else if (!isCh34xDevice){
                debugLog("setDTR called on non-CH34x device, state cached but not sent.");
            }
        }

        public void setRTS(boolean rts) {
            if (this.rtsState == rts && isCh34xDevice) return;
            this.rtsState = rts;
            if (isCh34xDevice && !closed && connection != null) {
                try {
                    applyDtrRtsState(1000);
                } catch (IOException e) {
                    Log.e(LOG_TAG, "IOException while setting RTS for CH34x", e);
                }
            } else if (!isCh34xDevice) {
                debugLog("setRTS called on non-CH34x device, state cached but not sent.");
            }
        }

        private void performCh34xInitialization(int timeoutMs) throws IOException {
            int ret;

            // 1. Начальная инициализация/рукопожатие (CH34X_REQ_SERIAL_INIT)
            // Request: 0xA1, Value: 0, Index: 0
            ret = connection.controlTransfer(ConnectedGps.REQTYPE_HOST_TO_DEVICE_VENDOR_DEVICE, CH34X_REQ_SERIAL_INIT,
                    0x0000, 0x0000, null, 0, timeoutMs);
            if (ret < 0) throw new IOException("CH34x init (0xA1) failed: " + ret);
            debugLog("CH34x init (0xA1) successful.");
            SystemClock.sleep(50); // Небольшая пауза

            // 2. Установка DTR и RTS
            // Устанавливаем начальные значения (по умолчанию оба false, измените если нужно)
            // this.dtrState = true; // Например, если GPS требует DTR high
            // this.rtsState = true; // Например, если GPS требует RTS high
            applyDtrRtsState(timeoutMs); // Применяем текущие dtrState и rtsState
            SystemClock.sleep(50);

            // 3. Установка скорости (Baud Rate)
            // Для CH340 это делается через CH34X_REQ_WRITE_REG (0x9A)
            // request=0x9A, value=0x1312 (регистр), index=константа_скорости
            int baudRateInt;
            if ("auto".equalsIgnoreCase(deviceSpeed) || deviceSpeed == null) {
                baudRateInt = 9600; // Дефолтная скорость для "auto" на CH340
                debugLog("CH34x: 'auto' speed detected, setting to default 9600bps.");
            } else {
                try {
                    baudRateInt = Integer.parseInt(deviceSpeed);
                } catch (NumberFormatException e) {
                    Log.w(LOG_TAG, "CH34x: Invalid deviceSpeed '" + deviceSpeed + "', defaulting to 9600bps.");
                    baudRateInt = 9600;
                }
            }

            int ch340BaudConstant;
            // Эти константы для request=0x9A, value=0x1312, index=ch340BaudConstant
            switch (baudRateInt) {
                case 9600:   ch340BaudConstant = CH34X_BAUD_VALUE_9600_FOR_0x1312; break; // 0xb282
                case 19200:  ch340BaudConstant = 0xd981; break;
                case 38400:  ch340BaudConstant = 0xec80; break;
                case 57600:  ch340BaudConstant = 0x96c0; break; // или 0x4640 (уточнить)
                case 115200: ch340BaudConstant = 0xA3C0; break; // A3C0
                // Добавьте другие скорости, если они известны для CH340 и этой команды
                default:
                    Log.w(LOG_TAG, "CH34x: Baud rate " + baudRateInt + " not directly supported by simplified setup. Using 9600 (0xb282).");
                    ch340BaudConstant = CH34X_BAUD_VALUE_9600_FOR_0x1312; // 9600
            }

            ret = connection.controlTransfer(ConnectedGps.REQTYPE_HOST_TO_DEVICE_VENDOR_DEVICE, CH34X_REQ_WRITE_REG,
                    0x1312, ch340BaudConstant, null, 0, timeoutMs);
            if (ret < 0) throw new IOException("CH34x set baud rate (0x9A, 0x1312, " + Integer.toHexString(ch340BaudConstant) + ") failed: " + ret);
            debugLog("CH34x baud rate set to " + baudRateInt + " (constant 0x" + Integer.toHexString(ch340BaudConstant) + ") successful.");
            SystemClock.sleep(50);

            // 4. Установка параметров линии (LCR: Data bits, Stop bits, Parity)
            // Для CH340(+): request=0x9A, value=0x2727, index=0x0000, data={LCR_byte, Break_byte}
            // Устанавливаем 8N1
            byte[] lcrPayload = new byte[]{CH34X_LCR_VALUE_8N1, (byte) 0x00}; // 8N1, break off
            ret = connection.controlTransfer(ConnectedGps.REQTYPE_HOST_TO_DEVICE_VENDOR_DEVICE, CH34X_REQ_WRITE_REG,
                    CH34X_LCR_REGISTER_ADDRESS_CH340_PLUS, CH34X_LCR_INDEX_CH340_PLUS, lcrPayload, lcrPayload.length, timeoutMs);
            if (ret < 0) throw new IOException("CH34x set LCR (8N1) failed: " + ret);
            debugLog("CH34x LCR (8N1) set successful.");
            SystemClock.sleep(50);

            debugLog("CH34x initialization complete.");
        }
        public ConnectedGps(UsbDevice device, String deviceSpeed) {
            /**
             * GPS bluetooth socket used for communication.
             */

            File gpsDev = null;

            debugLog("Searching interfaces, found " + String.valueOf(device.getInterfaceCount()));

            UsbInterface foundInterface = null;

            for (int j = 0; j < device.getInterfaceCount(); j++) {
                debugLog("Checking interface number " + String.valueOf(j));

                UsbInterface deviceInterface = device.getInterface(j);

                debugLog("Found interface of class " + String.valueOf(deviceInterface.getInterfaceClass()));

                // Finds an endpoint for the device by looking through all the device endpoints
                // and finding which one supports,

                debugLog("Searching endpoints of interface, found " + String.valueOf(deviceInterface.getEndpointCount()));

                UsbEndpoint foundInEndpoint = null;
                UsbEndpoint foundOutEndpoint = null;

                for (int i = deviceInterface.getEndpointCount() - 1; i > -1; i--) {
                    debugLog("Checking endpoint number " + String.valueOf(i));

                    UsbEndpoint interfaceEndpoint = deviceInterface.getEndpoint(i);

                    if (interfaceEndpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                        debugLog("Found IN Endpoint of type: " + String.valueOf(interfaceEndpoint.getType()));

                        if (interfaceEndpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {

                            debugLog("Is correct in endpoint");

                            foundInEndpoint = interfaceEndpoint;
                        }
                    }
                    if (interfaceEndpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                            debugLog("Found OUT Endpoint of type: " + String.valueOf(interfaceEndpoint.getType()));

                        if (interfaceEndpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {

                            debugLog("Is correct out endpoint");

                            foundOutEndpoint = interfaceEndpoint;
                        }
                    }

                    if ((foundInEndpoint != null) && (foundOutEndpoint != null)) {
                        endpointIn = foundInEndpoint;
                        endpointOut = foundOutEndpoint;
                        break;
                    }
                }

                if ((endpointIn != null) && (endpointOut != null)) {
                    foundInterface = deviceInterface;
                    break;
                }
            }

            intf = foundInterface;
            final int TIMEOUT = 1000;
            connection = usbManager.openDevice(device);

            if (intf != null) {
                debugLog("claiming interface");
                boolean resclaim = connection.claimInterface(intf, true);
                debugLog("data claim " + resclaim);

                if (resclaim) {
                    // Определяем, является ли это CH34x устройством
                    // Используем gpsVendorId и gpsProductId из внешнего класса USBGpsManager
                    if (USBGpsManager.this.gpsVendorId == 0x1A86 &&
                            (USBGpsManager.this.gpsProductId == 0x7523 || USBGpsManager.this.gpsProductId == 0x5523)) {
                        isCh34xDevice = true;
                        debugLog("CH340/CH341 detected. Performing CH34x initialization.");
                        try {
                            performCh34xInitialization(TIMEOUT);
                        } catch (IOException e) {
                            Log.e(LOG_TAG, "CH34x initialization failed", e);
                            // Обработка ошибки: закрыть соединение, уведомить пользователя.
                            // Важно корректно завершить, чтобы не было утечек ресурсов.
                            close(); // Закрываем ресурсы
                            // Прерываем дальнейшее создание объекта, т.к. он неработоспособен
                            throw new RuntimeException("CH34x initialization failed", e);
                        }
                    } else {
                        isCh34xDevice = false;
                        debugLog("Device is not CH340/CH341, skipping CH34x-specific initialization.");
                    }
                } else {
                    Log.e(LOG_TAG, "Failed to claim USB interface.");
                    close();
                    throw new RuntimeException("Failed to claim USB interface.");
                }
            } else {
                Log.e(LOG_TAG, "USB Interface not found.");
                close(); // Убедитесь, что connection закрывается, если intf null
                throw new RuntimeException("USB Interface not found.");
            }

            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            PrintStream tmpOut2 = null;

            tmpIn = new InputStream() {
                private final byte[] buffer = new byte[256];
                private final byte[] usbBuffer = new byte[64];
                private final byte[] oneByteBuffer = new byte[1];
                private final ByteBuffer bufferWrite = ByteBuffer.wrap(buffer);
                private final ByteBuffer bufferRead = (ByteBuffer) ByteBuffer.wrap(buffer).limit(0);
                private boolean closed = false;

                @Override
                public int read() throws IOException {
                    int b = 0;
                    if (debug) Log.d(LOG_TAG, "trying to read data");
                    int nb = 0;
                    while ((nb == 0) && (!closed)) {
                        nb = this.read(oneByteBuffer, 0, 1);
                    }
                    if (nb > 0) {
                        b = oneByteBuffer[0];
                    } else {
                        // TODO : if nb = 0 then we have a pb
                        b = -1;
                        Log.e(LOG_TAG, "data read() error code: " + nb);
                    }
                    if (b <= 0) {
                        Log.e(LOG_TAG, "data read() error: char " + b);
                    }
                    if (debug) Log.d(LOG_TAG, "data: " + b + " char: " + (char)b);
                    return b;
                }

                /* (non-Javadoc)
                 * @see java.io.InputStream#available()
                 */
                @Override
                public int available() throws IOException {
                    // TODO Auto-generated method stub
                    if (debug) Log.d(LOG_TAG, "data available "+bufferRead.remaining());
                    return bufferRead.remaining();
                }

                /* (non-Javadoc)
                 * @see java.io.InputStream#mark(int)
                 */
                @Override
                public void mark(int readlimit) {
                    // TODO Auto-generated method stub
                    if (debug) Log.d(LOG_TAG, "data mark");
                    super.mark(readlimit);
                }

                /* (non-Javadoc)
                 * @see java.io.InputStream#markSupported()
                 */
                @Override
                public boolean markSupported() {
                    // TODO Auto-generated method stub
                    if (debug) Log.d(LOG_TAG, "data markSupported");
                    return super.markSupported();
                }

                /* (non-Javadoc)
                 * @see java.io.InputStream#read(byte[], int, int)
                 */
                @Override
                public int read(byte[] buffer, int offset, int length)
                        throws IOException {
                    if (debug) Log.d(LOG_TAG, "data read buffer - offset: " + offset + " length: " + length);

                    int nb = 0;
                    ByteBuffer out = ByteBuffer.wrap(buffer, offset, length);
                    if ((!bufferRead.hasRemaining()) && (!closed)) {
                        if (debug) Log.i(LOG_TAG, "data read buffer empty " + Arrays.toString(usbBuffer));

                        int n = connection.bulkTransfer(endpointIn, usbBuffer, 64, 10000);

                        if (debug) Log.w(LOG_TAG, "data read: nb: " + n + " " + Arrays.toString(usbBuffer));

                        if (n > 0) {
                            if (n > bufferWrite.remaining()) {
                                bufferRead.rewind();
                                bufferWrite.clear();
                            }
                            bufferWrite.put(usbBuffer, 0, n);
                            bufferRead.limit(bufferWrite.position());
                            if (debug) Log.d(LOG_TAG, "data read: nb: " + n + " current: " + bufferRead.position() + " limit: " + bufferRead.limit() + " " + Arrays.toString(bufferRead.array()));
                        } else {
                            if (debug)
                                Log.e(LOG_TAG, "data read(buffer...) error: " + nb );
                        }
                    }
                    if (bufferRead.hasRemaining()) {
                        if (debug) Log.d(LOG_TAG, "data : asked: " + length + " current: " + bufferRead.position() + " limit: " + bufferRead.limit() + " " + Arrays.toString(bufferRead.array()));
                        nb = Math.min(bufferRead.remaining(), length);
                        out.put(bufferRead.array(), bufferRead.position() + bufferRead.arrayOffset(), nb);
                        bufferRead.position(bufferRead.position() + nb);
                      if (debug) Log.d(LOG_TAG, "data : given: " + nb + " current: " + bufferRead.position() + " limit: " + bufferRead.limit() + " " + Arrays.toString(bufferRead.array()));
                      if (debug) Log.d(LOG_TAG, "data : given: " + nb + " offset: " + offset + " " + Arrays.toString(buffer));
                    }
                    return nb;
                }

                /* (non-Javadoc)
                 * @see java.io.InputStream#read(byte[])
                 */

                @Override
                public int read(byte[] buffer) throws IOException {
                    // TODO Auto-generated method stub
                    log("data read buffer");
                    return super.read(buffer);
                }

                /* (non-Javadoc)
                 * @see java.io.InputStream#reset()
                 */
                @Override
                public synchronized void reset() throws IOException {
                    // TODO Auto-generated method stub
                    log("data reset");
                    super.reset();
                }

                /* (non-Javadoc)
                 * @see java.io.InputStream#skip(long)
                 */
                @Override
                public long skip(long byteCount) throws IOException {
                    // TODO Auto-generated method stub
                    log("data skip");
                    return super.skip(byteCount);
                }

                /* (non-Javadoc)
                 * @see java.io.InputStream#close()
                 */
                @Override
                public void close() throws IOException {
                    super.close();
                    closed = true;
                }
            };

            tmpOut = new OutputStream() {
                private final byte[] buffer = new byte[256];
                private final byte[] oneByteBuffer = new byte[1];
                private final ByteBuffer bufferWrite = ByteBuffer.wrap(buffer);
                private boolean closed = false;

                @Override
                public void write(int oneByte) throws IOException {
                    if (debug)
                        Log.d(LOG_TAG, "trying to write data (one byte): " + oneByte + " char: " + (char) oneByte);
                    oneByteBuffer[0] = (byte) oneByte;
                    this.write(oneByteBuffer, 0, 1);
                    if (debug)
                        Log.d(LOG_TAG, "writen data (one byte): " + oneByte + " char: " + (char) oneByte);
                }

                /* (non-Javadoc)
                 * @see java.io.OutputStream#write(byte[], int, int)
                 */
                @Override
                public void write(byte[] buffer, int offset, int count)
                        throws IOException {
                    if (debug)
                        Log.d(LOG_TAG, "trying to write data : " + Arrays.toString(buffer) + " offset " + offset + " count: " + count);
                    bufferWrite.clear();
                    bufferWrite.put(buffer, offset, count);
                    if (debug)
                        Log.d(LOG_TAG, "trying to write data : " + Arrays.toString(this.buffer));
                    int n = 0;
                    if (!closed) {
                        n = connection.bulkTransfer(endpointOut, this.buffer, count, TIMEOUT);
                    } else {
                        if (debug)
                            Log.e(LOG_TAG, "error while trying to write data: outputStream closed");
                    }
                    if (n != count) {
                        if (debug) {
                            Log.e(LOG_TAG, "error while trying to write data: " + Arrays.toString(this.buffer));
                            Log.e(LOG_TAG, "error while trying to write data: " + n + " bytes written when expecting " + count);
                        }
                        throw new IOException("error while trying to write data: " + Arrays.toString(this.buffer));
                    }
                    if (debug)
                        Log.d(LOG_TAG, "writen data (one byte): " + Arrays.toString(this.buffer));
                }

                /* (non-Javadoc)
                 * @see java.io.OutputStream#close()
                 */
                @Override
                public void close() throws IOException {
                    // TODO Auto-generated method stub
                    super.close();
                    closed = true;
                }

                /* (non-Javadoc)
                 * @see java.io.OutputStream#flush()
                 */
                @Override
                public void flush() throws IOException {
                    // TODO Auto-generated method stub
                    super.flush();
                }

                /* (non-Javadoc)
                 * @see java.io.OutputStream#write(byte[])
                 */
                @Override
                public void write(byte[] buffer) throws IOException {
                    // TODO Auto-generated method stub
                    super.write(buffer);
                }

            };



            try {
                tmpOut2 = new PrintStream(tmpOut, false, "US-ASCII");
            } catch (UnsupportedEncodingException e) {
                if (debug)
                    Log.e(LOG_TAG, "error while getting usb output streams", e);
            }

            in = tmpIn;
            out = tmpOut;
            out2 = tmpOut2;

            // We couldn't find an endpoint
            if (endpointIn == null || endpointOut == null) {
                if (debug)
                    Log.e(LOG_TAG, "We couldn't find an endpoint for the device, notifying");
                disable(R.string.msg_gps_provider_cant_connect);
                close();
                // throw new RuntimeException("Endpoints not found"); // Прервать, если эндпоинты критичны
                return; // или return, если конструктор может завершиться без ошибки
            }


            final int[] speedList = {Integer.parseInt(deviceSpeed), 1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800};
            final byte[] data = {(byte) 0xC0, 0x12, 0x00, 0x00, 0x00, 0x00, 0x08};
            //final ByteBuffer connectionSpeedBuffer = ByteBuffer.wrap(data, 0, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            //final byte[] datax = new byte[7];
            //final ByteBuffer connectionSpeedInfoBuffer = ByteBuffer.wrap(datax, 0, 7).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            //final int res1 = connection.controlTransfer(0x21, 34, 0, 0, null, 0, TIMEOUT);


            // --- ЛОГИКА УСТАНОВКИ СКОРОСТИ ---
            // Если это CH34x, скорость уже должна была быть установлена в performCh34xInitialization()
            // Поэтому старую логику для generic устройств нужно выполнять только если это НЕ CH34x
            if (!isCh34xDevice) {
                debugLog("Performing generic device speed setup.");
                // Ваша существующая логика с controlTransfer(0x21, 34, ...), setDeviceSpeed и autoConf
                final byte[] dataForSpeed = {(byte) 0xC0, 0x12, 0x00, 0x00, 0x00, 0x00, 0x08}; // Переименовал, чтобы не конфликтовать
                final ByteBuffer connectionSpeedBuffer = ByteBuffer.wrap(dataForSpeed, 0, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                final byte[] datax = new byte[7];
                final ByteBuffer connectionSpeedInfoBuffer = ByteBuffer.wrap(datax, 0, 7).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                final int res1 = connection.controlTransfer(0x21, 34, 0, 0, null, 0, TIMEOUT); // 0x21 - HOST_TO_DEVICE | CLASS | INTERFACE
                debugLog("Generic device init (0x21,34) result: " + res1);


                if (setDeviceSpeed) { // setDeviceSpeed - поле из USBGpsManager
                    debugLog("Setting connection speed (generic) to: " + deviceSpeed);
                    try {
                        connectionSpeedBuffer.putInt(0, Integer.parseInt(deviceSpeed));
                        int resSetSpeed = connection.controlTransfer(0x21, 32, 0, 0, dataForSpeed, dataForSpeed.length, TIMEOUT); // 0x21, 0x20 (SET_LINE_CODING)
                        debugLog("Generic set speed (0x21,32) to " + deviceSpeed + " result: " + resSetSpeed);
                        if (resSetSpeed < 0) {
                            Log.e(LOG_TAG, "Could not set speed (generic method)");
                        }
                    } catch (NumberFormatException | NullPointerException e) {
                        Log.e(LOG_TAG, "Could not set speed (generic method)", e);
                    }
                } else {
                    Thread autoConf = new Thread() {

                        /* (non-Javadoc)
                         * @see java.lang.Thread#run()
                         */
                        @Override
                        public void run() {
                            try {
                                // Get the current data rate from the device and transfer it into datax
                                int res0 = connection.controlTransfer(0xA1, 33, 0, 0, datax, 7, TIMEOUT);

                                // Datax is used in a byte buffer which this now turns into an integer
                                // and sets how preference speed to that speed
                                USBGpsManager.this.deviceSpeed = Integer.toString(connectionSpeedInfoBuffer.getInt(0));

                                // logs the bytes we got
                                debugLog("info connection: " + Arrays.toString(datax));
                                debugLog("info connection speed: " + USBGpsManager.this.deviceSpeed);

                                Thread.sleep(4000);
                                debugLog("trying to use speed in range: " + Arrays.toString(speedList));
                                for (int speed: speedList) {
                                    if (!ready && !closed) {
                                        // set a new datarate
                                        USBGpsManager.this.deviceSpeed = Integer.toString(speed);
                                        debugLog("trying to use speed " + speed);
                                        debugLog("initializing connection:  " + speed + " baud and 8N1 (0 bits no parity 1 stop bit");

                                        // Put that data rate into a new data byte array
                                        connectionSpeedBuffer.putInt(0, speed);

                                        // And set the device to that data rate
                                        int res2 = connection.controlTransfer(0x21, 32, 0, 0, data, 7, TIMEOUT);

                                        debugLog("data init " + res1 + " " + res2);
                                        Thread.sleep(4000);
                                    }
                                }
                                // And get the current data rate again
                                res0 = connection.controlTransfer(0xA1, 33, 0, 0, datax, 7, TIMEOUT);

                                debugLog("info connection: " + Arrays.toString(datax));
                                debugLog("info connection speed: " + connectionSpeedInfoBuffer.getInt(0));

                                if (!closed) {
                                    Thread.sleep(5000);
                                }
                            } catch (InterruptedException e) {
                                if (debug)
                                    Log.e(LOG_TAG, "autoconf thread interrupted", e);
                            } finally {
                                if ((!closed) && (!ready)){// || (lastRead + 4000 < SystemClock.uptimeMillis())) {
                                    setMockLocationProviderOutOfService();
                                    if (debug)
                                        Log.e(LOG_TAG, "Something went wrong in auto config");
                                    // cleanly closing everything...
                                    ConnectedGps.this.close();
                                    USBGpsManager.this.disableIfNeeded();
                                }
                            }
                        }

                    };
                    debugLog("trying to find speed");
                    ready = false;
                    autoConf.start();
                }
            } else {
                debugLog("CH34x device: Speed and LCR were set during CH34x initialization. Skipping generic speed setup.");
                // Для CH34x скорость должна быть установлена. Если `deviceSpeed`="auto",
                // то в `performCh34xInitialization` мы могли бы установить дефолтную (напр. 9600).
                // Если `deviceSpeed` задан, то мы пытались установить его.
                // `ready` установится, когда пойдут данные в `run()`.
            }

        }




        public boolean isReady() {
            return ready;
        }

        public void run() {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, "US-ASCII"), 256);

                // Sentence to read from the device
                String s;

                long now = SystemClock.uptimeMillis();

                // we will wait more at the beginning of the connection
                while ((enabled)  && (!closed)) {
                    try {
                        s = reader.readLine();
                    } catch (IOException e) {
                        s = null;
                    }

                    if (s != null) {
                        //Log.v(LOG_TAG, "data: "+System.currentTimeMillis()+" "+s);
                        if (notifyNmeaSentence(s + "\r\n")) {
                            ready = true;

                            if (problemNotified) {
                                problemNotified = false;
                                // reset eventual disabling cause
                                setDisableReason(0);
                                // connection is good so resetting the number of connection try
                                debugLog("connection is good so resetting the number of connection retries");
                                nbRetriesRemaining = maxConnectionRetries;
                                notificationManager.cancel(R.string.connection_problem_notification_title);
                            }
                        }
                        SystemClock.sleep(5);
                    } else {
                        log("data: not ready " + System.currentTimeMillis());
                        SystemClock.sleep(100);
                    }
                }
                if (closed) {

                    debugLog("Device connection closing, stopping read thread");
                } else {
                    debugLog("Provider disabled, stopping read thread");
                }
            } catch (Exception e) {
                if (debug)
                    Log.e(LOG_TAG, "error while getting data", e);
                setMockLocationProviderOutOfService();
            } finally {
                // cleanly closing everything...
                debugLog("Closing read thread");
                this.close();
                disableIfNeeded();
            }
        }

        public void close() {
            ready = false;
            closed = true;
            try {
                debugLog("closing USB GPS output stream");
                in.close();

            } catch (IOException e) {
                if (debug)
                    Log.e(LOG_TAG, "error while closing GPS NMEA output stream", e);

            } finally {
                try {
                    debugLog("closing USB GPS input streams");
                    out2.close();
                    out.close();

                } catch (IOException e) {
                    if (debug)
                        Log.e(LOG_TAG, "error while closing GPS input streams", e);

                } finally {
                    debugLog("releasing usb interface for connection: " + connection);

                    boolean released = false;
                    if (intf != null) {
                        released = connection.releaseInterface(intf);
                    }

                    if (released) {
                        debugLog("usb interface released for connection: " + connection);

                    } else if (intf != null) {
                        debugLog("unable to release usb interface for connection: " + connection);
                    } else {
                        debugLog("no interface to release");
                    }

                    debugLog("closing usb connection: " + connection);
                    connection.close();

                }
            }
        }
    }

    private boolean timeSetAlready;
    private final boolean shouldSetTime;

    private final Service callingService;
    private UsbDevice gpsDev;

    private final NmeaParser parser;
    private boolean enabled = false;
    private ExecutorService notificationPool;
    private ScheduledExecutorService connectionAndReadingPool;

    private final List<NmeaListener> nmeaListeners =
            Collections.synchronizedList(new LinkedList<NmeaListener>());

    private final SharedPreferences sharedPreferences;
    private ConnectedGps connectedGps;
    private int disableReason = 0;

    private static final String NOTIFICATION_CHANNEL_ID = "gps_service_notification";
    private final NotificationCompat.Builder connectionProblemNotificationBuilder;
    private final NotificationCompat.Builder serviceStoppedNotificationBuilder;

    private final Context appContext;
    private final NotificationManager notificationManager;

    private final int maxConnectionRetries;
    private int nbRetriesRemaining;

    private boolean enableNotifications = false;
    private boolean problemNotified = false;

    private boolean connected = false;
    private boolean setDeviceSpeed = false;
    private String deviceSpeed = "auto";
    private String defaultDeviceSpeed = "460800";

    private int gpsProductId = 424;
    private int gpsVendorId = 5446;

    /**
     * @param callingService
     * @param vendorId
     * @param productId
     * @param maxRetries
     */
    public USBGpsManager(Service callingService, int vendorId, int productId, int maxRetries) {
        this.gpsVendorId = vendorId;
        this.gpsProductId = productId;
        this.callingService = callingService;
        this.maxConnectionRetries = maxRetries + 1;
        this.nbRetriesRemaining = maxConnectionRetries;
        this.appContext = callingService.getApplicationContext();
        this.parser = new NmeaParser(10f, this.appContext);
        this.connectedGps = null;

        LocationManager locationManager = (LocationManager) callingService.getSystemService(Context.LOCATION_SERVICE);

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(callingService);

        enableNotifications = sharedPreferences.getBoolean(appContext.getString(R.string.pref_notifications_key), false);

        deviceSpeed = sharedPreferences.getString(
                USBGpsProviderService.PREF_GPS_DEVICE_SPEED,
                callingService.getString(R.string.defaultGpsDeviceSpeed)
        );

        shouldSetTime = sharedPreferences.getBoolean(USBGpsProviderService.PREF_SET_TIME, false);
        timeSetAlready = true;

        defaultDeviceSpeed = callingService.getString(R.string.defaultGpsDeviceSpeed);
        setDeviceSpeed = !deviceSpeed.equals(callingService.getString(R.string.autoGpsDeviceSpeed));
        notificationManager = (NotificationManager) callingService.getSystemService(Context.NOTIFICATION_SERVICE);
        parser.setLocationManager(locationManager);

        Intent stopIntent = new Intent(USBGpsProviderService.ACTION_STOP_GPS_PROVIDER);

        PendingIntent stopPendingIntent = PendingIntent.getService(appContext, 0, stopIntent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent restartIntent = new Intent(USBGpsProviderService.ACTION_START_GPS_PROVIDER);
        PendingIntent restartPendingIntent = PendingIntent.getService(appContext, 0, restartIntent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String channel ="";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                    new NotificationChannel(
                            NOTIFICATION_CHANNEL_ID,
                            appContext.getString(R.string.app_name),
                            NotificationManager.IMPORTANCE_LOW
                    )
            );
            channel = NOTIFICATION_CHANNEL_ID;
        }

        connectionProblemNotificationBuilder = new NotificationCompat.Builder(appContext, channel)
                .setContentIntent(stopPendingIntent)
                .setSmallIcon(R.drawable.ic_stat_notify);

        serviceStoppedNotificationBuilder = new NotificationCompat.Builder(appContext, channel)
                .setContentIntent(restartPendingIntent)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(appContext.getString(R.string.service_closed_because_connection_problem_notification_title))
                .setContentText(appContext.getString(R.string.service_closed_because_connection_problem_notification));

        usbManager = (UsbManager) callingService.getSystemService(Service.USB_SERVICE);


    }

    private void setDisableReason(int reasonId) {
        disableReason = reasonId;
    }

    /**
     * @return
     */
    public int getDisableReason() {
        return disableReason;
    }

    /**
     * @return true if the bluetooth GPS is enabled
     */
    public synchronized boolean isEnabled() {
        return enabled;
    }


    public boolean isMockLocationEnabled() {
        // Checks if mock location is enabled in settings

        boolean isMockLocation;

        try {
            //If marshmallow or higher then we need to check that this app is set as the provider
            AppOpsManager opsManager = (AppOpsManager)
                    appContext.getSystemService(Context.APP_OPS_SERVICE);
            isMockLocation =
                    opsManager.checkOp(
                            AppOpsManager.OPSTR_MOCK_LOCATION,
                            android.os.Process.myUid(),
                            BuildConfig.APPLICATION_ID
                    ) == AppOpsManager.MODE_ALLOWED;

        } catch (Exception e) {
            return false;
        }

        return isMockLocation;
    }

    /**
     * Starts the connection for the given usb gps device
     * @param device GPS device
     */
    private void openConnection(UsbDevice device) {
        if (!Objects.equals(getDeviceFromAttached(), device)) {
            return;
        }
        SystemClock.sleep(5000);
        // After 10 seconds we can assume the GPS must have the
        // correct time and so we are ready to assume the GPS can
        // set the correct time
        new Handler(appContext.getMainLooper())
                .postDelayed(
                        new Runnable() {
                            @Override
                            public void run() {
                                timeSetAlready = false;
                            }
                        },
                        10000
                );

        connected = true;

        if (setDeviceSpeed) {
            log("will set device speed: " + deviceSpeed);

        } else {
            log("will use default device speed: " + defaultDeviceSpeed);
            deviceSpeed = defaultDeviceSpeed;
        }

        log("starting usb reading task");
        connectedGps = new ConnectedGps(device, deviceSpeed);
        if (isEnabled()) {
            connectionAndReadingPool.execute(connectedGps);
            log("usb reading thread started");
        }
    }

    private UsbDevice getDeviceFromAttached() {
        debugLog("Checking all connected devices");
        for (UsbDevice connectedDevice : usbManager.getDeviceList().values()) {

            debugLog("Checking device: " + connectedDevice.getProductId() + " " + connectedDevice.getVendorId());

            if (connectedDevice.getVendorId() == gpsVendorId & connectedDevice.getProductId() == gpsProductId) {
                debugLog("Found correct device");

                return connectedDevice;
            }
        }

        return null;
    }

    /**
     * Enables the USB GPS Provider.
     *
     * @return
     */
    public synchronized boolean enable() {
        IntentFilter permissionFilter = new IntentFilter(ACTION_USB_PERMISSION);
        permissionFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        notificationManager.cancel(
                R.string.service_closed_because_connection_problem_notification_title
        );

        if (!enabled) {
            log("enabling USB GPS manager");

            if (!isMockLocationEnabled()) {
                if (debug)
                    Log.e(LOG_TAG, "Mock location provider OFF");
                disable(R.string.msg_mock_location_disabled);
                return this.enabled;

            } else if (PackageManager.PERMISSION_GRANTED  !=
                    ContextCompat.checkSelfPermission(
                            callingService, Manifest.permission.ACCESS_FINE_LOCATION)
                    ) {
                if (debug)
                    Log.e(LOG_TAG, "No location permission given");
                disable(R.string.msg_no_location_permission);
                return this.enabled;

            } else {
                gpsDev = getDeviceFromAttached();

                // This thread will be run by the executor at a delay of 1 second, and will be
                // run again if the read thread dies. It will run until maximum number of retries
                // is exceeded
                Runnable connectThread = new Runnable() {
                        @Override
                        public void run() {
                            try {
                                debugLog("Starting connect thread");
                                connected = false;
                                gpsDev = getDeviceFromAttached();

                                if (nbRetriesRemaining > 0) {
                                    if (connectedGps != null) {
                                        connectedGps.close();
                                    }

                                    if (gpsDev != null) {
                                        debugLog("GPS device: " + gpsDev.getDeviceName());

                                        PendingIntent permissionIntent = PendingIntent.getBroadcast(callingService, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
                                        UsbDevice device = gpsDev;

                                        if (device != null && usbManager.hasPermission(device)) {
                                            debugLog("We have permission, good!");
                                            openConnection(device);

                                        } else if (device != null) {
                                            debugLog("We don't have permission, so requesting...");
                                            usbManager.requestPermission(device, permissionIntent);

                                        } else {
                                            if (debug)
                                                Log.e(LOG_TAG, "Error while establishing connection: no device - " + gpsVendorId + ": " + gpsProductId);
                                        }
                                    } else {
                                        if (debug)
                                            Log.e(LOG_TAG, "Device not connected");
                                    }
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            } finally {
                                nbRetriesRemaining--;
                                if (!connected) {
                                    disableIfNeeded();
                                }
                            }

                        }
                    };

                this.enabled = true;
                callingService.registerReceiver(permissionAndDetachReceiver, permissionFilter);

                debugLog("USB GPS manager enabled");

                notificationPool = Executors.newSingleThreadExecutor();
                debugLog("starting connection and reading thread");
                connectionAndReadingPool = Executors.newSingleThreadScheduledExecutor();

                debugLog("starting connection to socket task");
                connectionAndReadingPool.scheduleWithFixedDelay(
                        connectThread,
                        1000,
                        3000,
                        TimeUnit.MILLISECONDS
                );
            }

        }
        return this.enabled;
    }

    /**
     * Disables the USB GPS Provider if the maximal number of connection retries is exceeded.
     * This is used when there are possibly non fatal connection problems.
     * In these cases the provider will try to reconnect with the usb device
     * and only after a given retries number will give up and shutdown the service.
     */
    private synchronized void disableIfNeeded() {
        if (enabled) {
            problemNotified = true;
            if (nbRetriesRemaining > 0) {
                // Unable to connect
                if (debug)
                    Log.e(LOG_TAG, "Connection ended");
                if(enableNotifications) {
                    String pbMessage = appContext.getResources()
                            .getQuantityString(
                                    R.plurals.connection_problem_notification,
                                    nbRetriesRemaining,
                                    nbRetriesRemaining
                            );


                    Notification connectionProblemNotification = connectionProblemNotificationBuilder
                            .setWhen(System.currentTimeMillis())
                            .setContentTitle(
                                    appContext.getString(R.string.connection_problem_notification_title)
                            )
                            .setContentText(pbMessage)
                            .setNumber(1 + maxConnectionRetries - nbRetriesRemaining)
                            .build();

                    notificationManager.notify(
                            R.string.connection_problem_notification_title,
                            connectionProblemNotification
                    );
                }

            } else {
                disable(R.string.msg_two_many_connection_problems);

            }
        }
    }

    /**
     * Disables the USB GPS provider.
     * <p>
     * It will:
     * <ul>
     * <li>close the connection with the bluetooth device</li>
     * <li>disable the Mock Location Provider used for the Usb GPS</li>
     * <li>stop the UsbGPS4Droid service</li>
     * </ul>
     * The reasonId parameter indicates the reason to close the bluetooth provider.
     * If its value is zero, it's a normal shutdown (normally, initiated by the user).
     * If it's non-zero this value should correspond a valid localized string id (res/values..../...)
     * which will be used to display a notification.
     *
     * @param reasonId the reason to close the bluetooth provider.
     */
    public synchronized void disable(int reasonId) {
        debugLog("disabling USB GPS manager reason: " + callingService.getString(reasonId));
        setDisableReason(reasonId);
        disable();
    }

    /**
     * Disables the Usb GPS provider.
     * <p>
     * It will:
     * <ul>
     * <li>close the connection with the bluetooth device</li>
     * <li>disable the Mock Location Provider used for the bluetooth GPS</li>
     * <li>stop the BlueGPS4Droid service</li>
     * </ul>
     * If the bluetooth provider is closed because of a problem, a notification is displayed.
     */
    public synchronized void disable() {
        notificationManager.cancel(R.string.connection_problem_notification_title);

        if (getDisableReason() != 0) {

            NotificationCompat.Builder partialServiceStoppedNotification =
                    serviceStoppedNotificationBuilder
                            .setWhen(System.currentTimeMillis())
                            .setAutoCancel(true)
                            .setContentTitle(
                                    appContext.getString(
                                            R.string.service_closed_because_connection_problem_notification_title
                                    )
                            )
                            .setContentText(
                                    appContext.getString(
                                            R.string.service_closed_because_connection_problem_notification,
                                            appContext.getString(getDisableReason())
                                    )
                            );

            // Make the correct notification to direct the user to the correct setting
            if (getDisableReason() == R.string.msg_mock_location_disabled) {
                PendingIntent mockLocationsSettingsIntent =
                        PendingIntent.getActivity(
                            appContext,
                            0,
                            new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        );

                partialServiceStoppedNotification
                        .setContentIntent(mockLocationsSettingsIntent)
                        .setStyle(
                                new NotificationCompat.BigTextStyle().bigText(
                                        appContext.getString(
                                                R.string.service_closed_because_connection_problem_notification,
                                                appContext.getString(R.string.msg_mock_location_disabled_full))
                                )
                        );

            } else if (getDisableReason() == R.string.msg_no_location_permission) {
                PendingIntent mockLocationsSettingsIntent = PendingIntent.getActivity(
                        appContext,
                        0,
                        new Intent(callingService, GpsInfoActivity.class),
                        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);

                USBGpsApplication.setLocationNotAsked();

               partialServiceStoppedNotification
                        .setContentIntent(mockLocationsSettingsIntent)
                        .setStyle(
                                new NotificationCompat.BigTextStyle().bigText(
                                        appContext.getString(
                                                R.string.service_closed_because_connection_problem_notification,
                                                appContext.getString(R.string.msg_no_location_permission)
                                        )
                                )
                        );

            }
            Notification serviceStoppedNotification = partialServiceStoppedNotification.build();
            notificationManager.notify(
                    R.string.service_closed_because_connection_problem_notification_title,
                    serviceStoppedNotification
            );
            SharedPreferences.Editor edit = sharedPreferences.edit();
            if (sharedPreferences.getInt(appContext.getString(R.string.pref_disable_reason_key), 0) != getDisableReason()) {
                edit.putInt(appContext.getString(R.string.pref_disable_reason_key), getDisableReason());
                edit.apply();
            }
            setDisableReason(0);
        }

        if (enabled) {
            debugLog("disabling USB GPS manager");
            callingService.unregisterReceiver(permissionAndDetachReceiver);

            enabled = false;
            connectionAndReadingPool.shutdown();

            Runnable closeAndShutdown = new Runnable() {
                @Override
                public void run() {
                    try {
                        connectionAndReadingPool.awaitTermination(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    if (!connectionAndReadingPool.isTerminated()) {
                        connectionAndReadingPool.shutdownNow();
                        if (connectedGps != null) {
                            connectedGps.close();
                        }

                    }
                }
            };

            notificationPool.execute(closeAndShutdown);
            nmeaListeners.clear();
            disableMockLocationProvider();
            notificationPool.shutdown();
            callingService.stopSelf();

            debugLog("USB GPS manager disabled");
        }
    }

    /**
     * Enables the Mock GPS Location Provider used for the bluetooth GPS.
     * In fact, it delegates to the NMEA parser.
     *
     * @param gpsName the name of the Location Provider to use for the bluetooth GPS
     * @param force   true if we want to force auto-activation of the mock location provider (and bypass user preference).
     */
    public void enableMockLocationProvider(String gpsName, boolean force) {
        if (parser != null) {
            debugLog("enabling mock locations provider: " + gpsName);
            parser.enableMockLocationProvider(gpsName, force);
        }
    }

    /**
     * Enables the Mock GPS Location Provider used for the bluetooth GPS.
     * In fact, it delegates to the NMEA parser.
     *
     * @param gpsName the name of the Location Provider to use for the bluetooth GPS
     */
    public void enableMockLocationProvider(String gpsName) {
        if (parser != null) {
            debugLog("enabling mock locations provider: " + gpsName);
            boolean force = sharedPreferences.getBoolean(
                    USBGpsProviderService.PREF_FORCE_ENABLE_PROVIDER, true
            );
            parser.enableMockLocationProvider(gpsName, force);
        }
    }

    /**
     * Disables the current Mock GPS Location Provider used for the bluetooth GPS.
     * In fact, it delegates to the NMEA parser.
     *
     * @see NmeaParser#disableMockLocationProvider()
     */
    public void disableMockLocationProvider() {
        if (parser != null) {
            debugLog("disabling mock locations provider");
            parser.disableMockLocationProvider();
        }
    }

    /**
     * Getter use to know if the Mock GPS Listener used for the bluetooth GPS is enabled or not.
     * In fact, it delegates to the NMEA parser.
     *
     * @return true if the Mock GPS Listener used for the bluetooth GPS is enabled.
     * @see NmeaParser#isMockGpsEnabled()
     */
    public boolean isMockGpsEnabled() {
        boolean mockGpsEnabled = false;
        if (parser != null) {
            mockGpsEnabled = parser.isMockGpsEnabled();
        }
        return mockGpsEnabled;
    }

    /**
     * Getter for the name of the current Mock Location Provider in use.
     * In fact, it delegates to the NMEA parser.
     *
     * @return the Mock Location Provider name used for the bluetooth GPS
     * @see NmeaParser#getMockLocationProvider()
     */
    public String getMockLocationProvider() {
        String mockLocationProvider = null;
        if (parser != null) {
            mockLocationProvider = parser.getMockLocationProvider();
        }
        return mockLocationProvider;
    }

    /**
     * Indicates that the bluetooth GPS Provider is out of service.
     * In fact, it delegates to the NMEA parser.
     *
     * @see NmeaParser#setMockLocationProviderOutOfService()
     */
    private void setMockLocationProviderOutOfService() {
        if (parser != null) {
            parser.setMockLocationProviderOutOfService();
        }
    }

    /**
     * Adds an NMEA listener.
     * In fact, it delegates to the NMEA parser.
     *
     * @param listener a {@link NmeaListener} object to register
     */
    public void addNmeaListener(NmeaListener listener) {
        if (!nmeaListeners.contains(listener)) {
            debugLog("adding new NMEA listener");
            nmeaListeners.add(listener);
        }
    }

    /**
     * Removes an NMEA listener.
     * In fact, it delegates to the NMEA parser.
     *
     * @param listener a {@link NmeaListener} object to remove
     */
    public void removeNmeaListener(NmeaListener listener) {
        debugLog("removing NMEA listener");
        nmeaListeners.remove(listener);
    }

    /**
     * Sets the system time to the given UTC time value
     * @param time UTC value HHmmss.SSS
     */
    private void setSystemTime(String time) {
        long parseTime = parser.parseNmeaTime(time);

        Log.v(LOG_TAG, "What?: " + parseTime);

        String timeFormatToybox =
                new SimpleDateFormat("MMddHHmmyyyy.ss", Locale.US).format(new Date(parseTime));

        String timeFormatToolbox =
                new SimpleDateFormat("yyyyMMdd.HHmmss", Locale.US).format(new Date(parseTime));

        debugLog("Setting system time to: " + timeFormatToybox);
        SuperuserManager suManager = SuperuserManager.getInstance();

        debugLog("toolbox date -s " + timeFormatToolbox+ "; toybox date " + timeFormatToybox +
                "; am broadcast -a android.intent.action.TIME_SET");

        if (suManager.hasPermission()) {
            suManager.asyncExecute("toolbox date -s " + timeFormatToolbox+ "; toybox date " + timeFormatToybox +
                    "; am broadcast -a android.intent.action.TIME_SET");
        } else {
            sharedPreferences
                    .edit()
                    .putBoolean(USBGpsProviderService.PREF_SET_TIME, false)
                    .apply();
        }
    }

    /**
     * Notifies the reception of a NMEA sentence from the USB GPS to registered NMEA listeners.
     *
     * @param nmeaSentence the complete NMEA sentence received from the USB GPS (i.e. $....*XY where XY is the checksum)
     * @return true if the input string is a valid NMEA sentence, false otherwise.
     */
    private boolean notifyNmeaSentence(final String nmeaSentence) {
        boolean res = false;
        if (enabled) {
            log("parsing and notifying NMEA sentence: " + nmeaSentence);
            String sentence = null;
            try {
                if (shouldSetTime && !timeSetAlready) {
                    parser.clearLastSentenceTime();
                }

                sentence = parser.parseNmeaSentence(nmeaSentence);

                if (shouldSetTime && !timeSetAlready) {
                    if (!parser.getLastSentenceTime().isEmpty()) {
                        setSystemTime(parser.getLastSentenceTime());
                        timeSetAlready = true;
                    }
                }

            } catch (SecurityException e) {
                if (debug)
                    Log.e(LOG_TAG, "error while parsing NMEA sentence: " + nmeaSentence, e);
                // a priori Mock Location is disabled
                sentence = null;
                disable(R.string.msg_mock_location_disabled);
            } catch (Exception e) {
                if (debug) {
                    Log.e(LOG_TAG, "Sentence not parsable");
                    Log.e(LOG_TAG, nmeaSentence);
                }
                e.printStackTrace();
            }
            final String recognizedSentence = sentence;
            final long timestamp = System.currentTimeMillis();
            if (recognizedSentence != null) {
                res = true;
                log("notifying NMEA sentence: " + recognizedSentence);
                if(enableNotifications) {
                    ((USBGpsApplication) appContext).notifyNewSentence(
                            recognizedSentence.replaceAll("(\\r|\\n)", "")
                    );
                }
                synchronized (nmeaListeners) {
                    for (final NmeaListener listener : nmeaListeners) {
                        notificationPool.execute(new Runnable() {
                            @Override
                            public void run() {
                                listener.onNmeaReceived(timestamp, recognizedSentence);
                            }
                        });
                    }
                }
            }
        }
        return res;
    }

    private void log(String message) {
        if (debug)
            Log.d(LOG_TAG, message);
    }


    private void debugLog(String message) {
        if (debug) Log.d(LOG_TAG, message);
    }
}
