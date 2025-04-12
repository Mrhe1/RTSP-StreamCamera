package com.example.supercamera.StreamPusher.PushStats;

public class TimeStamp {
        public final Long timeStamp;
        public final TimeStampStyle style;
        public enum TimeStampStyle {
                Captured,
                Encoded,
                Pushed
        }
        public TimeStamp (TimeStampStyle style, Long timeStamp) {
                this.style = style;
                this.timeStamp = timeStamp;
        }
}
