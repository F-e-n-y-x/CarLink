package com.byd.spi.ipc.cursor;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/** Debug-only decoder for the vendor provider's named Parcelable wire format. */
public final class BinderCursor {
    public static final class BinderParcelable implements Parcelable {
        public final IBinder binder;
        private BinderParcelable(Parcel source) { binder = source.readStrongBinder(); }
        public int describeContents() { return 0; }
        public void writeToParcel(Parcel out, int flags) { out.writeStrongBinder(binder); }
        public static final Parcelable.Creator<BinderParcelable> CREATOR = new Parcelable.Creator<BinderParcelable>() {
            public BinderParcelable createFromParcel(Parcel in) { return new BinderParcelable(in); }
            public BinderParcelable[] newArray(int size) { return new BinderParcelable[size]; }
        };
    }
}
