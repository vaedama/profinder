### Pre-requisites

- `brew install libsodium`


- [x] Secure session management
- [ ] Chat

### Session management

Workflow: 

1. `routes` file defines "root" path at "/" as: `GET / controllers.HomeController.index`.

Upon hitting this ["root" path](http://localhost:9000/), for instance, via browser, we 
invoke `HomeController`'s `index` action which serves the index page that is generated 
from `index.scala.html` which contains the login form. Since a session does not exist yet, 
the `userAction` simply forwards the incoming GET request.

2. After entering a username, clicking submit button invokes the "login" route defined in the
`routes` file: `POST /login controllers.LoginController.login`. Again, the `userAction`
simply forwards the incoming POST request.

In the `LoginController`'s `login` action, a new session is generated using the `SessionGenerator`. 
Below steps happen inside `SessionGenerator`'s `createSession`:

- Generate a new session-specific secret of size 32 bytes for Kalium's secret box
- Generate a new random session id and replicate this session id and secret across the distributed cluster
- Create a new CookieBaker using the secret and encode the `userInfo` as a cookie. 
CookieBaker can now serialize and deserialize the cookie data.
- Return the session id and cookie.

3. 

[index]() page serves index