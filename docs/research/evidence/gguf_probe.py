import struct, sys, os, json, collections
GT={0:'F32',1:'F16',2:'Q4_0',3:'Q4_1',6:'Q5_0',7:'Q5_1',8:'Q8_0',9:'Q8_1',10:'Q2_K',11:'Q3_K',12:'Q4_K',13:'Q5_K',14:'Q6_K',15:'Q8_K',16:'IQ2_XXS',17:'IQ2_XS',18:'IQ3_XXS',19:'IQ1_S',20:'IQ4_NL',21:'IQ3_S',22:'IQ2_S',23:'IQ4_XS',24:'I8',25:'I16',26:'I32',27:'I64',28:'F64',29:'IQ1_M',30:'BF16'}
SC={0:1,1:1,2:1,3:1,6:1,7:1,8:1,9:1,10:1,11:1,12:1,13:1,14:1,15:1,24:1,25:1,26:1,27:1,28:1,29:1,30:1}
def rstr(f):
    n=struct.unpack('<Q',f.read(8))[0]; return f.read(n).decode('utf-8','replace')
def rval(f,t):
    if t==0: return struct.unpack('<B',f.read(1))[0]
    if t==1: return struct.unpack('<b',f.read(1))[0]
    if t==2: return struct.unpack('<H',f.read(2))[0]
    if t==3: return struct.unpack('<h',f.read(2))[0]
    if t==4: return struct.unpack('<I',f.read(4))[0]
    if t==5: return struct.unpack('<i',f.read(4))[0]
    if t==6: return struct.unpack('<f',f.read(4))[0]
    if t==7: return struct.unpack('<B',f.read(1))[0]
    if t==8: return rstr(f)
    if t==9:
        et=struct.unpack('<I',f.read(4))[0]; n=struct.unpack('<Q',f.read(8))[0]
        return ('array',et,n)
    if t==10: return struct.unpack('<Q',f.read(8))[0]
    if t==11: return struct.unpack('<q',f.read(8))[0]
    if t==12: return struct.unpack('<d',f.read(8))[0]
    raise ValueError('type %d'%t)
def probe(p):
    with open(p,'rb') as f:
        magic=f.read(4); ver=struct.unpack('<I',f.read(4))[0]
        nt=struct.unpack('<Q',f.read(8))[0]; nkv=struct.unpack('<Q',f.read(8))[0]
        kv={}
        for _ in range(nkv):
            k=rstr(f); t=struct.unpack('<I',f.read(4))[0]
            v=rval(f,t)
            if t==9:
                et,n=v[1],v[2]
                if et==8:
                    for _i in range(n):
                        rstr(f)
                    kv[k]=('strlist',n)
                else:
                    kv[k]=('array',et,n)
                    f.read(0)
                    for _i in range(n):
                        rval(f,et)
            else:
                kv[k]=v
        tens=collections.Counter(); tbytes=0; n_tensors_ok=0; sample=[]
        for _ in range(nt):
            nm=rstr(f); nd=struct.unpack('<I',f.read(4))[0]
            dims=struct.unpack('<%dQ'%nd,f.read(8*nd))
            gt=struct.unpack('<I',f.read(4))[0]; off=struct.unpack('<Q',f.read(8))[0]
            tens[gt]+=1; n_tensors_ok+=1
            if len(sample)<3: sample.append((nm,list(dims),GT.get(gt,gt)))
        return {'magic':magic.decode('latin1'),'version':ver,'n_tensors':nt,'n_kv':nkv,
                'arch':kv.get('general.architecture'),'name':kv.get('general.name'),
                'file_type':kv.get('general.file_type'),'quant_version':kv.get('general.quantization_version'),
                'tok_model':kv.get('tokenizer.ggml.model'),'pre':kv.get('tokenizer.ggml.pre'),
                'tokens':kv.get('tokenizer.ggml.tokens'),'n_tensors_read':n_tensors_ok,
                'quant_hist':{GT.get(k,str(k)):v for k,v in tens.items()},'sample':sample}
for p in sys.argv[1:]:
    try:
        d=probe(p)
        print(json.dumps({'file':os.path.basename(p),'bytes':os.path.getsize(p),**d},ensure_ascii=False))
    except Exception as e:
        print(json.dumps({'file':os.path.basename(p),'bytes':os.path.getsize(p),'error':type(e).__name__+': '+str(e)[:120]},ensure_ascii=False))
