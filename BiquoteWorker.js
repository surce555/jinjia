export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    url.hostname = "biquote.io";
    
    // 构造发往 Biquote 的请求
    const newRequest = new Request(url.toString(), {
      headers: request.headers,
      method: request.method,
      body: request.body,
      redirect: 'follow'
    });
    
    // 发起请求并处理 CORS
    let response = await fetch(newRequest);
    response = new Response(response.body, response);
    response.headers.set('Access-Control-Allow-Origin', '*');
    
    return response;
  }
};
