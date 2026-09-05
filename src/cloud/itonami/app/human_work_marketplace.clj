(ns cloud.itonami.app.human-work-marketplace
  "Public, read-only HumanWorkRequest listing. Acceptance stays behind the
  Passkey session and exact eligibility check."
  (:require [clojure.string :as str]))

(defn page-html [brand-name]
  (str "<!doctype html><html lang=\"ja\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       "<title>Human Work — " (str/escape (or brand-name "Cloud Itonami")
                                           {\& "&amp;" \< "&lt;" \> "&gt;"})
       "</title><style>"
       ":root{color-scheme:light;font-family:system-ui,-apple-system,sans-serif;background:#f7f6f1;color:#17211b}"
       "body{margin:0}header,main{max-width:72rem;margin:auto;padding:2rem}header{padding-bottom:1rem}"
       "h1{font-size:clamp(2rem,6vw,4.5rem);letter-spacing:-.05em;margin:.2rem 0}.lead{max-width:44rem;color:#536057}"
       ".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(18rem,1fr));gap:1rem}"
       ".card{background:white;border:1px solid #d9ddd9;border-radius:1rem;padding:1.2rem;box-shadow:0 8px 24px #1520180d}"
       ".meta,.badges{display:flex;gap:.5rem;flex-wrap:wrap;color:#536057;font-size:.86rem}"
       ".badge{background:#e9f2ea;border-radius:999px;padding:.25rem .55rem}.pay{font-weight:700;margin-top:1rem}"
       "a{color:#075b34;font-weight:650}.empty{padding:2rem;border:1px dashed #aeb8b0;border-radius:1rem}"
       "</style></head><body><header><p>Cloud Itonami / Human computing</p>"
       "<h1>人にしかできない仕事。</h1><p class=\"lead\">資格・本人確認・場所・時間の条件を明示し、"
       "検収証拠と報酬状態を一つの依頼として扱います。正確な住所などは受諾後まで公開されません。</p>"
       "</header><main><div id=\"status\">公開案件を読み込んでいます…</div><section id=\"jobs\" class=\"grid\"></section></main>"
       "<script>const jobs=document.querySelector('#jobs'),status=document.querySelector('#status');"
       "const add=(p,t,c)=>{const e=document.createElement(t);if(c)e.className=c;e.textContent=p.appendText||'';p.el.append(e);return e};"
       "fetch('/api/human-work/requests').then(r=>r.ok?r.json():Promise.reject()).then(x=>{status.remove();"
       "if(!x.items.length){jobs.className='empty';jobs.textContent='現在公開中の案件はありません。';return;}"
       "x.items.forEach(j=>{const c=document.createElement('article');c.className='card';"
       "const h=document.createElement('h2');h.textContent=j.title;c.append(h);const s=document.createElement('p');s.textContent=j.summary;c.append(s);"
       "const m=document.createElement('div');m.className='meta';m.textContent=[j['work-mode'],j.location?.country,j.location?.region,j.location?.['service-area']].filter(Boolean).join(' · ');c.append(m);"
       "const b=document.createElement('div');b.className='badges';(j.requirements?.credentials||[]).forEach(q=>{const z=document.createElement('span');z.className='badge';z.textContent=q.type;b.append(z)});"
       "if(j.requirements?.identity){const z=document.createElement('span');z.className='badge';z.textContent='本人確認 '+j.requirements.identity['minimum-level'];b.append(z)}c.append(b);"
       "if(j.compensation){const p=document.createElement('p');p.className='pay';const d=j.compensation['asset-decimals']||6,a=j.compensation['amount-atomic'];const whole=a.length>d?a.slice(0,-d):'0',fraction=(a.length>d?a.slice(-d):a.padStart(d,'0')).replace(/0+$/,'');p.textContent=whole+(fraction?'.'+fraction:'')+' '+j.compensation.asset+' · '+j.compensation.network+' · '+j.compensation['settlement-status'];c.append(p)}"
       "const a=document.createElement('a');a.href='/?human-work='+encodeURIComponent(j.id);a.textContent='ログインして詳細・応募条件を確認';c.append(a);jobs.append(c)})"
       "}).catch(()=>{status.textContent='公開案件を取得できませんでした。';});</script></body></html>"))
