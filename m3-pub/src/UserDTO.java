/**
 * Session M3 Part A / B: replace this empty record header with the
 * fields Copilot suggests once you re-trigger ghost text after
 * opening User.java in a second tab. Then add a static
 * {@code fromUser(User)} mapper as described in Part B.
 */
public record UserDTO(String id, String email, String name) {
    public static UserDTO fromUser(User user) {
        return new UserDTO(
            String.valueOf(user.getId()),
            user.getEmail(),
            user.getName()
        );
    }

    public static void main(String[] args) {
        User user = new User(1, "John Doe", "john.doe@example.com", true);
        User user2 = new User(2, "Jane Smith", "jane.smith@example.com", false);
        UserDTO userDTO = UserDTO.fromUser(user);
        UserDTO userDTO2 = UserDTO.fromUser(user2);
        System.out.println(userDTO);
        System.out.println(userDTO2);
    }
}