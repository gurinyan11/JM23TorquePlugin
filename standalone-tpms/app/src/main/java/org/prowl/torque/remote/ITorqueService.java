package org.prowl.torque.remote;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/** Binder interface copied from the working JM23 plugin. */
public interface ITorqueService extends IInterface {
    String[] sendCommandGetResponse(String header, String command) throws RemoteException;

    boolean setPIDInformation(String name, String shortName, String unit,
                              float max, float min, float value,
                              String stringValue) throws RemoteException;

    abstract class Stub extends Binder implements ITorqueService {
        public static final String DESCRIPTOR = "org.prowl.torque.remote.ITorqueService";
        static final int TRANSACTION_sendCommandGetResponse = IBinder.FIRST_CALL_TRANSACTION + 11;
        static final int TRANSACTION_setPIDInformation = IBinder.FIRST_CALL_TRANSACTION + 29;

        public Stub() { attachInterface(this, DESCRIPTOR); }

        public static ITorqueService asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof ITorqueService) return (ITorqueService) iin;
            return new Proxy(obj);
        }

        @Override public IBinder asBinder() { return this; }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static final class Proxy implements ITorqueService {
            private final IBinder remote;
            Proxy(IBinder remote) { this.remote = remote; }
            @Override public IBinder asBinder() { return remote; }

            @Override public String[] sendCommandGetResponse(String header, String command)
                    throws RemoteException {
                Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(header);
                    data.writeString(command);
                    boolean ok = remote.transact(TRANSACTION_sendCommandGetResponse, data, reply, 0);
                    if (!ok) throw new RemoteException("Torque transaction rejected: sendCommandGetResponse");
                    reply.readException();
                    return reply.createStringArray();
                } finally { reply.recycle(); data.recycle(); }
            }

            @Override public boolean setPIDInformation(String name, String shortName, String unit,
                                             float max, float min, float value,
                                             String stringValue) throws RemoteException {
                Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(name);
                    data.writeString(shortName);
                    data.writeString(unit);
                    data.writeFloat(max);
                    data.writeFloat(min);
                    data.writeFloat(value);
                    data.writeString(stringValue);
                    boolean ok = remote.transact(TRANSACTION_setPIDInformation, data, reply, 0);
                    if (!ok) throw new RemoteException("Torque transaction rejected: setPIDInformation");
                    reply.readException();
                    return reply.readInt() != 0;
                } finally { reply.recycle(); data.recycle(); }
            }
        }
    }
}
