## 1.1
Part A: public record UserDTO(String id, String email, String name)

Once User.java was opened:
public static UserDTO fromUser(User user) {
    return new UserDTO(user.id(), user.email(), user.name());
}

Part B: 
getters were used instead of user.id(), user.email(), user,name()
make build ran successfully.
make test: 3 tests failed.

Part C:
The annotations, response types, and status codes consistent with the hand-written endpoint
