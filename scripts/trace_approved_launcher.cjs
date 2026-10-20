// Convert the approved white mark into native vector contours without redesigning it.
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const root = path.resolve(__dirname,'..');
function simplify(points, tolerance) {
  if(points.length < 3) return points;
  const first=points[0], last=points.at(-1);
  const dx=last[0]-first[0],dy=last[1]-first[1],length=dx*dx+dy*dy;
  let distance=0,index=0;
  for(let i=1;i<points.length-1;i++) {
    const p=points[i],t=length?Math.max(0,Math.min(1,((p[0]-first[0])*dx+(p[1]-first[1])*dy)/length)):0;
    const d=(p[0]-first[0]-t*dx)**2+(p[1]-first[1]-t*dy)**2;
    if(d>distance){distance=d;index=i;}
  }
  return distance>tolerance*tolerance ? [...simplify(points.slice(0,index+1),tolerance).slice(0,-1),...simplify(points.slice(index),tolerance)] : [first,last];
}
(async()=>{
  const {data,info}=await sharp(path.join(root,'design/app-icon/splate-icon-approved.png')).removeAlpha().raw().toBuffer({resolveWithObject:true});
  const w=info.width,h=info.height;
  let left=w,top=h,right=0,bottom=0;
  for(let y=0;y<h;y++)for(let x=0;x<w;x++){
    const i=(y*w+x)*3,r=data[i],g=data[i+1],b=data[i+2];
    if(b>150&&b-r>70&&g-r>30){left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);}
  }
  const diameter=Math.max(right-left+1,bottom-top+1),cx=(left+right+1)/2,cy=(top+bottom+1)/2;
  const mask=new Uint8Array(w*h);
  for(let y=Math.floor(top+diameter*.17);y<top+diameter*.83;y++)for(let x=Math.floor(left+diameter*.17);x<left+diameter*.83;x++){
    const i=(y*w+x)*3,r=data[i],g=data[i+1],b=data[i+2];
    if(Math.min(r,g,b)>220&&Math.max(r,g,b)-Math.min(r,g,b)<18)mask[y*w+x]=1;
  }
  const edges=new Map();const key=(x,y)=>y*(w+1)+x;
  const edge=(x,y,xx,yy)=>{const k=key(x,y);if(!edges.has(k))edges.set(k,[]);edges.get(k).push(key(xx,yy));};
  for(let y=1;y<h-1;y++)for(let x=1;x<w-1;x++)if(mask[y*w+x]){
    if(!mask[(y-1)*w+x])edge(x,y,x+1,y);
    if(!mask[y*w+x+1])edge(x+1,y,x+1,y+1);
    if(!mask[(y+1)*w+x])edge(x+1,y+1,x,y+1);
    if(!mask[y*w+x-1])edge(x,y+1,x,y);
  }
  const contours=[];
  while(edges.size){
    const start=edges.keys().next().value;let current=start;const points=[];
    do{
      points.push([current%(w+1),Math.floor(current/(w+1))]);
      const next=edges.get(current);if(!next)throw new Error('Broken contour');
      const following=next.pop();if(!next.length)edges.delete(current);current=following;
    }while(current!==start);
    const area=Math.abs(points.reduce((a,p,i)=>{const n=points[(i+1)%points.length];return a+p[0]*n[1]-n[0]*p[1];},0)/2);
    if(area>20)contours.push(simplify([...points,points[0]],1.3));
  }
  if(contours.length<3||contours.length>8)throw new Error(`Unexpected mark contours: ${contours.length}`);
  const coordinate=(value,center)=>(54+(value-center)*72/diameter).toFixed(3);
  const area=points=>Math.abs(points.reduce((sum,p,i)=>{const n=points[(i+1)%points.length];return sum+p[0]*n[1]-n[0]*p[1];},0));
  contours.sort((a,b)=>area(b)-area(a));
  const contourPath=points=>points.map((p,i)=>`${i?'L':'M'}${coordinate(p[0],cx)},${coordinate(p[1],cy)}`).join(' ')+' Z';
  // The two largest contours are the shield's outside and inside. The S stays untouched.
  const shield=contours.slice(0,2).map(contourPath).join(' ');
  const letter=contours.slice(2).map(contourPath).join(' ');
  fs.writeFileSync(path.join(root,'app/src/main/res/drawable/ic_launcher_mark.xml'),`<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n    <!-- Approved contours; slightly heavier shield for small launcher sizes. -->\n    <path android:fillColor="#FFFFFF" android:fillType="evenOdd" android:strokeColor="#FFFFFF" android:strokeWidth="0.7" android:strokeLineJoin="round" android:strokeLineCap="round" android:pathData="${shield}" />\n    <path android:fillColor="#FFFFFF" android:fillType="evenOdd" android:pathData="${letter}" />\n</vector>\n`);
  console.log(JSON.stringify({blueBadge:{left,top,right,bottom},contours:contours.length,vertices:contours.reduce((n,p)=>n+p.length,0)}));
})().catch(e=>{console.error(e);process.exitCode=1;});
