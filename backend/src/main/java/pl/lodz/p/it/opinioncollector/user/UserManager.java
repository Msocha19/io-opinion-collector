package pl.lodz.p.it.opinioncollector.user;


import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserManager {

    private final UserRepository userRepository;

}
