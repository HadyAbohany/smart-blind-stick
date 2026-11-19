package eg.sbs.benchmark;

import java.util.Arrays;

public final class VisionLabCheck {
    static int checks;
    static void check(boolean value,String message) {checks++;if(!value)throw new AssertionError(message);}
    static void near(float a,float b) {check(Math.abs(a-b)<.0001,"transform/value mismatch: "+a+" != "+b);}
    static DepthMath.Estimate sample(float[] values,byte[] confidence,DepthMath.Source source,long age) {
        return DepthMath.sample(values,confidence,40,40,0,0,1,1,DepthMath.Transform.identity(),source,age);
    }
    public static void main(String[] args) {
        var raw=DepthMath.Source.ARCORE_RAW;var metric=DepthMath.Source.DEPTH_ANYTHING_METRIC;
        float[] grid=new float[1600];Arrays.fill(grid,.7f);byte[] conf=new byte[1600];Arrays.fill(conf,(byte)255);
        var estimate=sample(grid,conf,raw,0);check(estimate.available(),"confident raw depth");near(estimate.meters(),.7f);
        check(!sample(grid,null,raw,0).available(),"raw confidence required");
        check(!sample(grid,conf,raw,501).available(),"stale rejected");check(!sample(grid,conf,raw,-1).available(),"future rejected");
        check(!sample(grid,conf,DepthMath.Source.RELATIVE,0).available(),"relative cannot become meters");
        check(sample(grid,null,metric,0).available(),"monocular explicitly labeled metric");
        Arrays.fill(conf,(byte)127);check(!sample(grid,conf,raw,0).available(),"low confidence rejected");Arrays.fill(conf,(byte)255);
        Arrays.fill(grid,Float.NaN);check(!sample(grid,conf,raw,0).available(),"NaN rejected");
        Arrays.fill(grid,0);check(!sample(grid,conf,raw,0).available(),"missing depth rejected");
        for(int y=0;y<40;y++)for(int x=0;x<40;x++) grid[y*40+x]=x<20?.5f:3f;
        check(!sample(grid,conf,raw,0).available(),"inconsistent ROI rejected");
        Arrays.fill(grid,.7f);grid[20*40+20]=8;near(sample(grid,conf,raw,0).meters(),.7f);
        check(!DepthMath.sample(grid,conf,40,40,-.1f,0,1,1,DepthMath.Transform.identity(),raw,0).available(),"invalid ROI");
        check(!DepthMath.sample(grid,conf,40,40,0,0,.001f,.001f,DepthMath.Transform.identity(),raw,0).available(),"tiny ROI insufficient samples");
        var rotate=DepthMath.Transform.unrotate(90);near(rotate.x(.2f,.3f),.3f);near(rotate.y(.2f,.3f),.8f);
        var composed=rotate.then(DepthMath.Transform.unrotate(270));near(composed.x(.2f,.3f),.2f);near(composed.y(.2f,.3f),.3f);
        var off=new DepthMath.Transform(1,0,2,0,1,0);
        check(!DepthMath.sample(grid,conf,40,40,0,0,1,1,off,raw,0).available(),"out of camera coverage");
        var attention=new DepthMath.Attention();
        check(!attention.update(estimate,0,true),"first hit insufficient");check(!attention.update(estimate,100,true),"two hits insufficient");check(attention.update(estimate,200,true),"three stable hits");
        check(!attention.update(sample(grid,null,metric,0),300,true),"monocular never automatically warns");
        check(!attention.update(estimate,400,false),"not foreground");
        check(!attention.update(estimate,2000,true),"long gap resets");
        attention.update(estimate,2100,true);attention.update(estimate,2200,true);check(attention.update(estimate,2300,true),"recovers with fresh samples");
        check(!attention.update(estimate,2300,true),"same timestamp not reused");
        float[][] rows=new float[6][38];
        rows[0]=row(0,40,320,280,.9f,139); rows[1]=row(0,40,320,280,.8f,139); rows[2]=row(0,40,320,280,.7f,2);
        rows[3]=row(0,40,320,280,Float.NaN,1); rows[4]=row(0,40,320,280,.9f,140); rows[5]=row(0,40,320,280,.9f,1.5f);
        var boxes=LabBoxes.decode(rows,140,true,.45f,0,40,320,240);
        check(boxes.size()==1,"end-to-end segmentation extras ignored and duplicates removed");check(boxes.get(0).label()==139,"expanded ID preserved");near(boxes.get(0).y1(),0);near(boxes.get(0).y2(),1);
        float[][] channels=new float[144][1];channels[0][0]=160;channels[1][0]=160;channels[2][0]=160;channels[3][0]=160;channels[143][0]=.85f;
        boxes=LabBoxes.decode(channels,140,false,.45f,0,0,320,320);check(boxes.size()==1&&boxes.get(0).label()==139,"raw expanded head");near(boxes.get(0).x1(),.25f);
        check(LabBoxes.decode(new float[][]{null,new float[]{1}},140,true,.45f,0,0,320,320).isEmpty(),"malformed rows skipped");
        try{LabBoxes.decode(new float[3][1],140,false,.45f,0,0,320,320);throw new AssertionError("malformed shape accepted");}catch(IllegalArgumentException expected){checks++;}
        check(CaptionGuard.inspect("الصورة تُظهر رجلين يقفان بجانب مركبة نقل مدنية، ورجل ثالث يسير على الرصيف.").accepted(),"real native Arabic caption accepted");
        for(String text:new String[]{"", "A chair in a room", "الكرسي على مسافة 2 متر أمامك", "الطاولة تبعد ٢ متر", "امشي للأمام بجانب الكرسي", "اتجه نحو الباب أمامك", "تحقق بالعصا أمام الكرسي", "الكرسي يبعد مترين عنك", "توقّف أمام الكرسي", "لف يمين أمام الطاولة", "أمامك كرسي\ufffd"}) check(!CaptionGuard.inspect(text).accepted(),"unsupported caption blocked: "+text);
        System.out.println("Vision lab checks passed: "+checks);
    }
    private static float[] row(float x1,float y1,float x2,float y2,float score,float cls) {float[] r=new float[38];r[0]=x1;r[1]=y1;r[2]=x2;r[3]=y2;r[4]=score;r[5]=cls;return r;}
}
