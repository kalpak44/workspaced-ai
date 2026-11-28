"""Synthetic loopback Responses server. Only used inside a --network none test container."""
import http.server,json,uuid
class Handler(http.server.BaseHTTPRequestHandler):
 def log_message(self,*args): pass
 def do_POST(self):
  body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
  rid='resp_'+uuid.uuid4().hex
  response={'id':rid,'object':'response','created_at':1,'status':'completed','model':body.get('model','gpt-6.1-sol'),'output':[{'id':'msg_mock_'+uuid.uuid4().hex,'type':'message','role':'assistant','status':'completed','content':[{'type':'output_text','text':'WORKSPACED_OK','annotations':[]}]}], 'usage':{'input_tokens':12,'output_tokens':7,'total_tokens':19,'input_tokens_details':{'cached_tokens':3},'output_tokens_details':{'reasoning_tokens':2}}}
  item=response['output'][0]
  events=[{'type':'response.created','response':{**response,'status':'in_progress','output':[]}}, {'type':'response.output_item.added','output_index':0,'item':{**item,'status':'in_progress','content':[]}}, {'type':'response.content_part.added','item_id':item['id'],'output_index':0,'content_index':0,'part':{'type':'output_text','text':'','annotations':[]}}, {'type':'response.output_text.delta','item_id':item['id'],'output_index':0,'content_index':0,'delta':'WORKSPACED_OK'}, {'type':'response.output_text.done','item_id':item['id'],'output_index':0,'content_index':0,'text':'WORKSPACED_OK'}, {'type':'response.output_item.done','output_index':0,'item':item}, {'type':'response.completed','response':response}]
  data=''.join('event: '+e['type']+'\ndata: '+json.dumps(e)+'\n\n' for e in events).encode()
  self.send_response(200);self.send_header('Content-Type','text/event-stream');self.send_header('Content-Length',len(data));self.end_headers();self.wfile.write(data)
http.server.HTTPServer(('127.0.0.1',5005),Handler).serve_forever()
