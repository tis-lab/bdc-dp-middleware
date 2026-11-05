package org.biodatacatalyst.middleware.summary;
/** Dependency-free boundary check: run with -ea after compilation alongside SummaryAgeBins. */
public class SummaryAgeBinsCheck {
    public static void main(String[] args) {
        int[] bounds={16437,20089,23742,29220};
        for(int i=0;i<3;i++) {
            var b=SummaryAgeBins.BANDS.get(i);
            if(b.fromDays()!=bounds[i] || b.toDays()!=bounds[i+1]) throw new AssertionError(b);
        }
        int[] days={16436,16437,20088,20089,23741,23742,29219,29220};
        int[] expected={0,1,1,1,1,1,1,0};
        for(int i=0;i<days.length;i++) {
            int n=0; for(var b:SummaryAgeBins.BANDS) if(days[i]>=b.fromDays()&&days[i]<b.toDays())n++;
            if(n!=expected[i])throw new AssertionError("Boundary: "+days[i]);
        }
        if(!SummaryAgeBins.aggregation().containsKey("filter"))throw new AssertionError("Missing recorded-age scope");
        System.out.println("PASS: age day conversion, inclusive/exclusive boundaries, non-overlap and recorded-age scope");
    }
}
